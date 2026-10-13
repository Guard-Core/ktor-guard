package com.guardcore.ktor

import com.guardcore.config.SecurityConfig
import com.guardcore.config.SecurityConfigBuilder
import com.guardcore.errors.GuardRedisError
import com.guardcore.protocols.AgentHandlerProtocol
import com.guardcore.protocols.GuardResponse
import com.guardcore.util.IpExtraction
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.Hook
import io.ktor.server.application.ServerReady
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.RoutingResolveResult
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.path
import io.ktor.util.AttributeKey
import kotlinx.coroutines.launch

/** The installed [GuardCoreMiddleware] handle, retrievable with `application.guardCore()`. */
public class GuardCoreApplicationPluginInstance
internal constructor(
    public val middleware: GuardCoreMiddleware,
)

/**
 * Plugin configuration (install-time):
 * ```kotlin
 * install(GuardCore) {
 *     config = SecurityConfigBuilder().apply {
 *         whitelist = listOf("10.0.0.1")
 *     }.build()
 *     decorator = GuardRouteDecorator(config)   // per-route config surface
 *     agentHandler = ...                        // guard-agent-jvm handler (optional)
 * }
 * ```
 */
public class GuardCorePluginConfig {
  /** The engine [SecurityConfig]. Required. */
  public var config: SecurityConfig? = null

  /**
   * The per-route decorator adopted by the middleware. Routes decorated through the
   * [com.guardcore.ktor] route extensions resolve their configs per request.
   */
  public var decorator: GuardRouteDecorator? = null

  /** The guard-agent-jvm handler, e.g. `GuardAgentTelemetry(agent)` from guard-agent-jvm. */
  public var agentHandler: AgentHandlerProtocol? = null

  /**
   * Optional hook returning the connecting peer IP for a call (the TS hono adapter's
   * connectingIpResolver seam). Defaults to the engine's connection point remoteHost; wire it when
   * the deployment sits behind a proxy that surfaces the peer address another way.
   */
  public var connectingIpResolver: ((ApplicationCall) -> String?)? = null

  /** Builder convenience: `config { ... }` instead of an explicit SecurityConfigBuilder. */
  public fun config(build: SecurityConfigBuilder.() -> Unit) {
    config = SecurityConfigBuilder().apply(build).build()
  }
}

/** The user-visible plugin name Ktor reports (W0 scaffold constant, kept). */
public const val GUARD_PLUGIN_NAME: String = "GuardCore"

internal val GUARD_MIDDLEWARE_KEY =
    AttributeKey<GuardCoreApplicationPluginInstance>("GuardCoreMiddleware")

internal val GUARD_REQUEST_START_KEY = AttributeKey<Long>("GuardCoreRequestStartNanos")
internal val GUARD_PROCESSED_KEY = AttributeKey<Boolean>("GuardCoreProcessed")
internal val GUARD_WRAPPER_KEY = AttributeKey<KtorGuardRequest>("GuardCoreWrappedRequest")

/** Request-state attribute the middleware sets for the response-phase modifier. */
internal const val GUARD_BYPASS_ATTR: String = "guard_bypass_all_response"

/**
 * Request-side hook: the reference adapter is a Starlette BaseHTTPMiddleware whose dispatch wraps
 * the downstream app and returns before call_next on a block. Ktor's application-plugin onCall runs
 * at the Plugins phase with no short-circuit primitive, so this hook intercepts the same phase and
 * FINISHES the call pipeline after responding: a guard block prevents the route handler from
 * executing, exactly like the reference dispatch ordering.
 */
internal object GuardRequestHook : Hook<suspend (io.ktor.server.application.PipelineCall) -> Unit> {
  override fun install(
      pipeline: ApplicationCallPipeline,
      handler: suspend (io.ktor.server.application.PipelineCall) -> Unit,
  ) {
    pipeline.intercept(ApplicationCallPipeline.Plugins) {
      if (call.response.isCommitted) {
        return@intercept
      }
      handler(call)
      if (call.response.isCommitted) {
        finish()
      }
    }
  }
}

/**
 * The GuardCore Ktor server plugin: the true port of the fastapi-guard ASGI middleware.
 *
 * Request side ([GuardRequestHook]): lazy single-flight initialization (503 + Retry-After on a
 * redis failure at init), request wrapping, route-id stamping, CORS preflight, path-exclusion
 * passthrough, client-ip extraction, route-config resolution, the all-checks bypass, the security
 * pipeline, and behavioral usage rules - in the reference dispatch order (middleware.py:578-611).
 *
 * Response side ([ResponseBodyReadyForSend]): guard-produced responses get the CorsHandler response
 * headers; normal responses run the facade's process_response exit path (metrics, security headers,
 * CORS, behavioral return rules, modifier), and route-bypassed responses pass through the custom
 * response modifier.
 *
 * Lifecycle: [ServerReady] triggers eager initialization when lazy_init is false (the
 * guard_lifespan analog; the single-flight lock makes the race with early traffic safe), and the
 * middleware keeps its lazy first-request path otherwise.
 */
public val GuardCore: ApplicationPlugin<GuardCorePluginConfig> =
    createApplicationPlugin(
        name = GUARD_PLUGIN_NAME,
        createConfiguration = ::GuardCorePluginConfig,
    ) {
      val configValue =
          checkNotNull(pluginConfig.config) {
            "install(GuardCore) requires a config: install(GuardCore) { config = ... }"
          }
      val middleware =
          GuardCoreMiddleware(configValue, pluginConfig.decorator, pluginConfig.agentHandler)
      middleware.connectingIpResolver = pluginConfig.connectingIpResolver
      application.attributes.put(
          GUARD_MIDDLEWARE_KEY,
          GuardCoreApplicationPluginInstance(middleware),
      )

      on(MonitoringEvent(ServerReady)) { _ ->
        application.launch {
          try {
            middleware.eagerInitialize()
          } catch (e: Exception) {
            middleware.logger.severe("Guard eager initialization failed: $e")
          }
        }
      }

      on(GuardRequestHook) { call ->
        handleGuardRequest(call, middleware)
      }

      on(ResponseBodyReadyForSend) { call, content ->
        if (call.attributes.getOrNull(GUARD_PROCESSED_KEY) == true) {
          injectCorsIntoResponse(call)
          return@on
        }
        call.attributes.put(GUARD_PROCESSED_KEY, true)
        val guardRequest = call.attributes.getOrNull(GUARD_WRAPPER_KEY) ?: return@on
        val startNanos = call.attributes.getOrNull(GUARD_REQUEST_START_KEY)
        val responseTime =
            if (startNanos != null) {
              (System.nanoTime() - startNanos) / RESPONSE_TIME_SECONDS
            } else {
              0.0
            }
        handleGuardResponse(this, call, content, middleware, guardRequest, responseTime)
      }
    }

private const val RESPONSE_TIME_SECONDS = 1_000_000_000.0

/** Retrieves the installed middleware handle from the application. */
public fun Application.guardCore(): GuardCoreApplicationPluginInstance =
    attributes.get(GUARD_MIDDLEWARE_KEY)

/** Retrieves the installed middleware handle when present. */
public fun Application.guardCoreOrNull(): GuardCoreApplicationPluginInstance? =
    attributes.getOrNull(GUARD_MIDDLEWARE_KEY)

/**
 * Resolves the matched [io.ktor.server.routing.Route] for the call ahead of routing (the
 * Plugins-phase hook runs before the RoutingRoot interceptor). This is the Ktor analog of the
 * reference adapter replaying Starlette's route matching in the middleware (middleware.py
 * `_resolve_route`): the framework's own public resolver answers.
 */
internal suspend fun resolveRouteNode(
    call: io.ktor.server.application.PipelineCall,
): io.ktor.server.routing.Route? {
  val routingRoot = call.application.pluginOrNull(RoutingRoot) ?: return null
  return try {
    when (val result = RoutingResolveContext(routingRoot, call, emptyList()).resolve()) {
      is RoutingResolveResult.Success -> result.route
      is RoutingResolveResult.Failure -> null
    }
  } catch (_: Exception) {
    null
  }
}

/**
 * The guard request hook body: the reference dispatch chain (middleware.py:578-611) minus the
 * call_next leg, which Ktor's suspend dispatch performs after this hook returns.
 */
internal suspend fun handleGuardRequest(
    call: io.ktor.server.application.PipelineCall,
    middleware: GuardCoreMiddleware,
) {
  // Python dispatch step 1: ensure initialized; a redis failure at init is a 503.
  try {
    middleware.ensureInitialized()
  } catch (e: GuardRedisError) {
    middleware.logger.severe("Redis unavailable during initialization: $e")
    val response = middleware.redisUnavailableResponse()
    middleware.injectCorsHeaders(response, headerMap(call))
    call.attributes.put(GUARD_PROCESSED_KEY, true)
    call.respondGuardResponse(response)
    return
  }

  // Python dispatch step 2: wrap the framework call into the facade protocol.
  val connectingIp = middleware.connectingIpResolver?.invoke(call) ?: call.request.origin.remoteHost
  val guardRequest = KtorGuardRequest(call, connectingIp)
  call.attributes.put(GUARD_WRAPPER_KEY, guardRequest)

  // Python dispatch step 3 (_populate_guard_state): the stamped route id rides the state.
  val routeNode = resolveRouteNode(call)
  routeNode?.attributes?.getOrNull(GuardRouteIdAttribute)?.let { routeId ->
    guardRequest.state.setAttr("guard_route_id", routeId)
  }
  guardRequest.state.setAttr("guard_endpoint_id", endpointIdFor(routeNode))
  middleware.decorator?.let { decorator ->
    guardRequest.state.setAttr("guard_decorator", decorator)
  }

  // Python dispatch step 4: CORS preflight.
  val preflight = middleware.handlePreflight(guardRequest)
  if (preflight != null) {
    middleware.injectCorsHeaders(preflight, guardRequest.headers)
    call.attributes.put(GUARD_PROCESSED_KEY, true)
    call.respondGuardResponse(preflight)
    return
  }

  // Python dispatch step 5: path-exclusion passthrough / no-client-address handling.
  val passthrough = middleware.handlePassthrough(guardRequest)
  if (passthrough != null) {
    middleware.injectCorsHeaders(passthrough, guardRequest.headers)
    call.attributes.put(GUARD_PROCESSED_KEY, true)
    call.respondGuardResponse(passthrough)
    return
  }

  // Python dispatch step 6: client ip extraction (cached into request state).
  val clientIp =
      IpExtraction.extractClientIp(guardRequest, middleware.config, middleware.agentHandler)

  // Python dispatch step 7: route config resolution.
  val routeConfig = middleware.routeResolver.getRouteConfig(guardRequest)

  // Python dispatch step 8: the route-level all-checks bypass.
  val bypass = middleware.handleSecurityBypass(guardRequest, routeConfig)
  if (bypass != null) {
    middleware.injectCorsHeaders(bypass, guardRequest.headers)
    call.attributes.put(GUARD_PROCESSED_KEY, true)
    call.respondGuardResponse(bypass)
    return
  }

  // Python dispatch step 9: the security pipeline.
  val blocked = middleware.handlePipelineBlock(guardRequest)
  if (blocked != null) {
    middleware.injectCorsHeaders(blocked, guardRequest.headers)
    call.attributes.put(GUARD_PROCESSED_KEY, true)
    call.respondGuardResponse(blocked)
    return
  }

  // Python dispatch step 10: behavioral usage rules.
  if (routeConfig != null && routeConfig.behaviorRules.isNotEmpty() && clientIp.isNotEmpty()) {
    middleware.processUsageRules(guardRequest, clientIp, routeConfig)
  }

  // Hand off to routing: the handler executes after this hook returns.
  call.attributes.put(GUARD_REQUEST_START_KEY, System.nanoTime())
}

/**
 * The guard response hook body: guard-produced responses only get CORS response headers;
 * route-bypassed responses pass through the custom response modifier; normal responses run the
 * facade's process_response exit path (middleware.py:482).
 */
internal suspend fun handleGuardResponse(
    context: ResponseBodyReadyForSend.Context,
    call: ApplicationCall,
    content: OutgoingContent,
    middleware: GuardCoreMiddleware,
    guardRequest: KtorGuardRequest,
    responseTime: Double,
) {
  val routeConfig = middleware.routeResolver.getRouteConfig(guardRequest)
  val view = outgoingResponseView(call, content)

  if (guardRequest.state.getAttr(GUARD_BYPASS_ATTR) == true) {
    // Route bypassed all checks: the downstream response passes through the modifier only
    // (the reference bypass path applies the modifier, not the full exit path).
    val modified = middleware.middlewareResponseFactory.applyModifier(view)
    reflectResponse(context, call, content, view, modified)
    injectCorsIntoResponse(call)
    return
  }

  val processed = middleware.processResponse(guardRequest, view, responseTime, routeConfig)
  reflectResponse(context, call, content, view, processed)
  injectCorsIntoResponse(call)
}

/**
 * Materializes a [GuardResponse] view over the outgoing response: status and headers from the call
 * and the outgoing content, body bytes only when the outgoing content carries them (streaming
 * bodies stay unread, mirroring the reference adapter's bounded-read contract for responses).
 */
internal fun outgoingResponseView(call: ApplicationCall, content: OutgoingContent): GuardResponse {
  val status = content.status?.value ?: call.response.status()?.value ?: 200
  val response = KtorGuardResponse(status, "")
  copyResponseHeaders(call, content, response)
  content.contentType?.let { response.headers["content-type"] = it.toString() }
  materializeBody(content)?.let { response.overrideBody(it) }
  return response
}

/** Copies the committed response headers plus the outgoing content headers into the view. */
private fun copyResponseHeaders(
    call: ApplicationCall,
    content: OutgoingContent,
    view: GuardResponse,
) {
  val committedHeaders: Headers = call.response.headers.allValues()
  for (name in committedHeaders.names()) {
    view.headers[name] = committedHeaders.get(name) ?: continue
  }
  for (name in content.headers.names()) {
    if (isEngineManagedHeader(name)) {
      continue
    }
    view.headers[name] = content.headers.get(name) ?: continue
  }
}

/** The outgoing body bytes when the content is buffered (respondText/respondBytes shapes). */
private fun materializeBody(content: OutgoingContent): ByteArray? =
    (content as? OutgoingContent.ByteArrayContent)?.bytes()

private fun isEngineManagedHeader(name: String): Boolean =
    name.equals(HttpHeaders.ContentType, ignoreCase = true) ||
        name.equals(HttpHeaders.ContentLength, ignoreCase = true) ||
        name.equals(HttpHeaders.TransferEncoding, ignoreCase = true)

/**
 * Writes a processed [GuardResponse] back into the outgoing response.
 *
 * Buffered bodies (respondText/respondBytes: [OutgoingContent.ByteArrayContent]) rebuild the
 * content with the full processed header set, so header ADDITION and REPLACEMENT both carry exactly
 * (Starlette single-valued semantics); status replaces through the content status, which the engine
 * commits last. Streamed bodies get additions only, appended to the response headers, and a status
 * change through the response status channel.
 */
internal suspend fun reflectResponse(
    context: ResponseBodyReadyForSend.Context,
    call: ApplicationCall,
    content: OutgoingContent,
    view: GuardResponse,
    processed: GuardResponse,
) {
  val sourceBytes = materializeBody(content)
  if (sourceBytes == null) {
    reflectStreamed(call, content, view, processed)
    return
  }
  reflectBuffered(context, call, content, processed, sourceBytes)
}

/** Streamed bodies: header additions and a status change only (no replayable body). */
private suspend fun reflectStreamed(
    call: ApplicationCall,
    content: OutgoingContent,
    view: GuardResponse,
    processed: GuardResponse,
) {
  for ((name, value) in processed.headers) {
    if (isEngineManagedHeader(name)) {
      continue
    }
    if (view.headers[name] == null) {
      call.response.headers.append(name, value, safeOnly = false)
    }
  }
  if (processed.statusCode != (call.response.status()?.value ?: content.status?.value)) {
    call.response.status(HttpStatusCode.fromValue(processed.statusCode))
  }
}

/** Buffered bodies: rebuild the content with the merged header set, status, and body. */
private suspend fun reflectBuffered(
    context: ResponseBodyReadyForSend.Context,
    call: ApplicationCall,
    content: OutgoingContent,
    processed: GuardResponse,
    sourceBytes: ByteArray,
) {
  val finalHeaders = mergedHeaderSet(call, content, processed)
  val status =
      if (processed.statusCode != (content.status?.value ?: call.response.status()?.value)) {
        HttpStatusCode.fromValue(processed.statusCode)
      } else {
        content.status
      }
  val processedBody: ByteArray = processed.body ?: sourceBytes
  val finalBody = if (processedBody.contentEquals(sourceBytes)) sourceBytes else processedBody
  context.transformBodyTo(
      GuardProcessedContent(
          source = content,
          body = finalBody,
          headers = finalHeaders,
          status = status,
      ),
  )
}

/**
 * The full final header set: the outgoing content headers, then the committed response headers,
 * then the processed map applied as single-valued replacements (Starlette semantics).
 */
private fun mergedHeaderSet(
    call: ApplicationCall,
    content: OutgoingContent,
    processed: GuardResponse,
): Headers {
  val builder = HeadersBuilder()
  for (name in content.headers.names()) {
    builder.appendAll(name, content.headers.getAll(name) ?: emptyList())
  }
  val committedHeaders: Headers = call.response.headers.allValues()
  for (name in committedHeaders.names()) {
    builder.appendAll(name, committedHeaders.getAll(name) ?: emptyList())
  }
  for ((name, value) in processed.headers) {
    if (isEngineManagedHeader(name)) {
      continue
    }
    builder.set(name, value)
  }
  return builder.build()
}

/**
 * The processed outgoing content: carries the full final header set (the engine commits content
 * headers, so replacements land exactly) and the (possibly modified) body bytes.
 */
internal class GuardProcessedContent(
    private val source: OutgoingContent,
    private val body: ByteArray,
    override val headers: Headers,
    override val status: HttpStatusCode?,
) : OutgoingContent.ReadChannelContent() {
  override val contentType: io.ktor.http.ContentType?
    get() = source.contentType

  override val contentLength: Long
    get() = body.size.toLong()

  override fun readFrom(): io.ktor.utils.io.ByteReadChannel = io.ktor.utils.io.ByteReadChannel(body)
}

/** Guard-produced responses only need the CorsHandler response headers (middleware.py:508). */
internal fun injectCorsIntoResponse(call: ApplicationCall) {
  val middleware = call.application.attributes.getOrNull(GUARD_MIDDLEWARE_KEY)?.middleware ?: return
  val cors = middleware.corsHandler ?: return
  for ((name, value) in cors.buildResponseHeaders(headerMap(call))) {
    if (call.response.headers[name] != value) {
      call.response.headers.append(name, value, safeOnly = false)
    }
  }
}

/** The request-header map the CorsHandler reads on the response side. */
internal fun headerMap(call: ApplicationCall): Map<String, String> =
    call.attributes.getOrNull(GUARD_WRAPPER_KEY)?.headers
        ?: KtorGuardRequest.buildJoinedHeaders(call.request.headers)

/** Best-effort endpoint id for behavioral processing (the guard_endpoint_id twin). */
internal fun endpointIdFor(routeNode: io.ktor.server.routing.Route?): String {
  if (routeNode == null) {
    return ""
  }
  val method = methodSelectorOf(routeNode)
  return "${method?.value ?: "*"}:${routeNode.path()}"
}
