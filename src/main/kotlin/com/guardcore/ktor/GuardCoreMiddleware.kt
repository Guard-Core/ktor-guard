package com.guardcore.ktor

import com.guardcore.config.LogFormat
import com.guardcore.config.SecurityConfig
import com.guardcore.core.behavioral.BehavioralContext
import com.guardcore.core.behavioral.BehavioralProcessor
import com.guardcore.core.bypass.BypassContext
import com.guardcore.core.bypass.BypassHandler
import com.guardcore.core.checks.ChecksFactory
import com.guardcore.core.checks.SecurityCheckPipeline
import com.guardcore.core.events.CompositeAgentHandler
import com.guardcore.core.events.MetricsCollector
import com.guardcore.core.events.SecurityEventBus
import com.guardcore.core.initialization.HandlerInitializer
import com.guardcore.core.responses.ErrorResponseFactory
import com.guardcore.core.responses.ResponseContext
import com.guardcore.core.routing.RouteConfigResolver
import com.guardcore.core.routing.RoutingContext
import com.guardcore.core.validation.RequestValidator
import com.guardcore.core.validation.ValidationContext
import com.guardcore.decorators.BaseSecurityDecorator
import com.guardcore.decorators.RouteConfig
import com.guardcore.handlers.CloudManager
import com.guardcore.handlers.CorsHandler
import com.guardcore.handlers.HandlerAgentSink
import com.guardcore.handlers.RateLimitManager
import com.guardcore.handlers.RedisManager
import com.guardcore.handlers.isPreflight
import com.guardcore.protocols.AgentHandlerProtocol
import com.guardcore.protocols.GeoIPHandler
import com.guardcore.protocols.GuardMiddlewareProtocol
import com.guardcore.protocols.GuardRequest
import com.guardcore.protocols.GuardResponse
import com.guardcore.protocols.GuardResponseFactory
import com.guardcore.protocols.MiddlewareEventBusProtocol
import com.guardcore.protocols.MiddlewareResponseFactoryProtocol
import com.guardcore.protocols.RedisHandlerProtocol
import com.guardcore.util.AgentEvents.invokeErrorHook
import com.guardcore.util.LoggingUtils.setupCustomLogging
import java.util.logging.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Port of the fastapi-guard `SecurityMiddleware` (guard/middleware.py) over the guard-core-jvm
 * facade: the [GuardMiddlewareProtocol] implementation the facade checks and handlers call back
 * into, plus the per-request dispatch chain the GuardCore Ktor plugin drives.
 *
 * ktor-guard contains NO security logic: every decision is the facade's pipeline, bypass handler,
 * response factory, and validators; this class is the wiring plus the Ktor lifecycle bridges.
 *
 * Initialization mirrors the reference lazy-init contract: the first request initializes the
 * pipeline and handlers under a single-flight lock (or [eagerInitialize] runs it at server start
 * through the plugin's ServerReady hook); a lazy_init=False config that never saw a startup hook
 * logs the same warning as the reference (middleware.py:126).
 */
public class GuardCoreMiddleware(
    override val config: SecurityConfig,
    /** The user-held per-route decorator adopted by this middleware (may be null). */
    public val decorator: GuardRouteDecorator?,
    /**
     * An externally supplied agent handler (guard-agent-jvm); null until the agent family ships.
     */
    public val injectedAgentHandler: AgentHandlerProtocol?,
) : GuardMiddlewareProtocol {
  override val logger: Logger =
      setupCustomLogging(
          config.customLogFile,
          logFormat = if (config.logFormat == LogFormat.JSON) "json" else "text",
      )

  override var lastCloudIpRefresh: Int = 0

  override val suspiciousRequestCounts: MutableMap<String, MutableMap<String, Int>> = linkedMapOf()

  /** Python `self.rate_limit_handler = RateLimitManager(config)` (middleware.py:64). */
  public val rateLimitManager: RateLimitManager = RateLimitManager.getInstance(config)

  override val guardDecorator: BaseSecurityDecorator?
    get() = decorator

  /** Python `__init__` geo handler gate (middleware.py:68): only when country rules need it. */
  public val geoIpHandlerValue: GeoIPHandler? =
      if (config.whitelistCountries.isNotEmpty() || config.blockedCountries.isNotEmpty()) {
        config.geoIpHandler
      } else {
        null
      }

  override val geoIpHandler: GeoIPHandler?
    get() = geoIpHandlerValue

  public val redisManager: RedisManager? =
      if (config.enableRedis) {
        RedisManager.getInstance(config)
      } else {
        null
      }

  public var agentDegraded: Boolean = false
    private set

  public var agentHandlerValue: AgentHandlerProtocol? = injectedAgentHandler
    private set

  override val agentHandler: AgentHandlerProtocol?
    get() = agentHandlerValue

  public var securityEventBus: SecurityEventBus =
      SecurityEventBus(agentHandler as? HandlerAgentSink, config, geoIpHandler)
    private set

  public var metricsCollector: MetricsCollector =
      MetricsCollector(agentHandler as? HandlerAgentSink, config)
    private set

  public val handlerInitializer: HandlerInitializer =
      HandlerInitializer(
          config = config,
          redisHandler = redisManager,
          agentHandler = agentHandler,
          geoIpHandler = geoIpHandler,
          rateLimitHandler = rateLimitManager,
          guardDecorator = decorator,
      )

  override val routeResolver: RouteConfigResolver =
      RouteConfigResolver(RoutingContext(config, logger, decorator))

  /** Python `_build_event_dependent_contexts` (middleware.py:247) results, rebuilt on init. */
  public var errorResponseFactory: ErrorResponseFactory
    private set

  public var validator: RequestValidator
    private set

  public var bypassHandler: BypassHandler
    private set

  public var behavioralProcessor: BehavioralProcessor
    private set

  /** The response factory seam checks call through (https_enforcement.py:53 call shape). */
  public val middlewareResponseFactory: MiddlewareResponseFactoryProtocol =
      AdapterResponseFactory(this)

  override val responseFactory: MiddlewareResponseFactoryProtocol?
    get() = middlewareResponseFactory

  init {
    // The facade's detection-engine seams (G-1/G-5 user-agent matcher + straddle overlap) and
    // W3-backed config seams (G-2 geo factory, G-6 cloud checker) register idempotently; the
    // python family wires the same seams through module imports.
    com.guardcore.util.DetectionEngineWiring.register()
    com.guardcore.handlers.HandlerSeams.register()

    val contexts = buildEventDependentContexts()
    errorResponseFactory = contexts.errorResponseFactory
    validator = contexts.validator
    bypassHandler = contexts.bypassHandler
    behavioralProcessor = contexts.behavioralProcessor

    if (config.enableAgent && injectedAgentHandler == null) {
      // The guard-agent-jvm artifact ships in W7 (specs/impl/jvm.md family table); until it
      // lands, an injected handler is the only agent source. Mirror the reference
      // `_on_agent_init_failed` degrade path (middleware.py:301).
      val exc =
          IllegalStateException(
              "Agent enabled but no agent handler was provided. Install guard-agent-jvm and " +
                  "pass agentHandler to install(GuardCore), or disable enable_agent.",
          )
      onAgentInitFailed(exc, exc.message ?: "Agent initialization failed")
    }
  }

  override val eventBus: MiddlewareEventBusProtocol?
    get() = securityEventBus

  override val rateLimitHandler: Any?
    get() = rateLimitManager

  override val guardResponseFactory: GuardResponseFactory = KtorGuardResponseFactory()

  public var securityPipeline: SecurityCheckPipeline? = null
    private set

  /** The plugin-configured connecting-IP resolver (see GuardCorePluginConfig). */
  public var connectingIpResolver: ((io.ktor.server.application.ApplicationCall) -> String?)? = null

  private val initializationLock = Mutex()
  private var initialized: Boolean = false
  private var lazyInitWarned: Boolean = false

  /** Python `self._cors_handler` (middleware.py:118): null unless enable_cors. */
  public val corsHandler: CorsHandler? = if (config.enableCors) CorsHandler(config) else null

  // ----------------------------------------------------------- initialization

  /**
   * Python `initialize` (middleware.py:712): build the pipeline, initialize redis handlers and
   * agent integrations, and adopt the composite handler when one exists.
   */
  public suspend fun initialize() {
    buildSecurityPipeline()

    handlerInitializer.initializeRedisHandlers()

    handlerInitializer.initializeAgentIntegrations()

    val composite = handlerInitializer.compositeHandler
    if (composite != null) {
      // Python initialize() lines 719-726: adopt the composite as the agent handler and
      // rebuild the event bus + metrics collector around it, then rebuild the contexts.
      agentHandlerValue = CompositeAgentProtocol(composite)
      securityEventBus = handlerInitializer.buildEventBus(geoIpHandler)
      metricsCollector = handlerInitializer.buildMetricsCollector()
      val contexts = buildEventDependentContexts()
      errorResponseFactory = contexts.errorResponseFactory
      validator = contexts.validator
      bypassHandler = contexts.bypassHandler
      behavioralProcessor = contexts.behavioralProcessor
    }
    initialized = true
    logger.info("Guard security middleware initialized")
  }

  /**
   * Boot-time initialization through the plugin's ServerReady hook (the guard_lifespan analog):
   * true eager initialization instead of first-request init.
   */
  public suspend fun eagerInitialize() {
    if (config.lazyInit) {
      return
    }
    ensureInitialized()
  }

  /** Python `_ensure_initialized` (middleware.py:147): single-flight lazy init. */
  public suspend fun ensureInitialized() {
    if (initialized) {
      return
    }
    initializationLock.withLock {
      if (initialized) {
        return
      }
      warnIfEagerInitNotHonored()
      initialize()
    }
  }

  /** Python `_warn_if_eager_init_not_honored` (middleware.py:126). */
  private fun warnIfEagerInitNotHonored() {
    if (config.lazyInit || lazyInitWarned) {
      return
    }
    lazyInitWarned = true
    logger.warning(
        "lazy_init=False was set, but GuardCoreMiddleware is initializing on " +
            "the first request instead of at server start. Ktor initializes the plugin " +
            "at install time; call eagerInitialize() from your module (or rely on the " +
            "plugin's ServerReady hook) to warm the pipeline before traffic arrives.",
    )
  }

  /** Python `_build_security_pipeline` (middleware.py:295). */
  private fun buildSecurityPipeline() {
    val pipeline = ChecksFactory.buildDefaultPipeline(this)
    securityPipeline = pipeline
    logger.info(
        "Security pipeline initialized with ${pipeline.size} checks: " +
            "${pipeline.getCheckNames()} " +
            "(${ChecksFactory.DEFAULT_CHECK_CLASSES.size - pipeline.size} skipped)",
    )
  }

  /** Python `_build_event_dependent_contexts` (middleware.py:247). */
  private fun buildEventDependentContexts(): EventDependentContexts {
    val responseContext =
        ResponseContext(
            config = config,
            logger = logger,
            metricsCollector = metricsCollector,
            agentHandler = agentHandler,
            guardDecorator = decorator,
            responseFactory = middlewareResponseFactory,
        )
    val responseFactory = ErrorResponseFactory(responseContext)

    val validationContext = ValidationContext(config, logger, securityEventBus)
    val validator = RequestValidator(validationContext)

    val bypassContext =
        BypassContext(
            config = config,
            logger = logger,
            eventBus = securityEventBus,
            routeResolver = routeResolver,
            responseFactory = responseFactory,
            validator = validator,
        )
    val bypassHandler = BypassHandler(bypassContext)

    val behavioralContext =
        BehavioralContext(
            config = config,
            logger = logger,
            eventBus = securityEventBus,
            guardDecorator = decorator,
            behaviorTracker = handlerInitializer.behaviorTracker,
        )
    val behavioralProcessor = BehavioralProcessor(behavioralContext)

    return EventDependentContexts(responseFactory, validator, bypassHandler, behavioralProcessor)
  }

  private class EventDependentContexts(
      val errorResponseFactory: ErrorResponseFactory,
      val validator: RequestValidator,
      val bypassHandler: BypassHandler,
      val behavioralProcessor: BehavioralProcessor,
  )

  /** Python `_on_agent_init_failed` (middleware.py:301). */
  private fun onAgentInitFailed(exc: Exception, message: String) {
    agentDegraded = true
    invokeErrorHook(
        { stage, hookExc, context ->
          config.onError?.invoke(stage, hookExc, context)
        },
        "agent_init",
        exc,
        linkedMapOf(),
    )
    if (config.agentStrict) {
      throw exc
    }
    logger.severe(message)
    logger.warning("Continuing without agent functionality")
  }

  // ----------------------------------------------------------- dispatch chain

  /**
   * Python `_handle_preflight` (middleware.py:525): CORS preflight requests run the pipeline; a
   * block wins, otherwise the preflight response goes out.
   */
  public suspend fun handlePreflight(request: GuardRequest): GuardResponse? {
    val cors = corsHandler ?: return null
    if (!isPreflight(request.method, request.headers)) {
      return null
    }
    val pipeline = securityPipeline ?: return null
    val blocking = pipeline.execute(request)
    if (blocking != null) {
      return blocking
    }
    val preflight = cors.buildPreflightResponse(request.headers)
    val response = KtorGuardResponse(preflight.statusCode, preflight.body)
    for ((name, value) in preflight.headers) {
      response.headers[name] = value
    }
    return response
  }

  /**
   * Python `_handle_passthrough` (middleware.py:541): path exclusions and the no-client-address
   * fail-secure decision. The passthrough path never materializes a downstream response here
   * (exclusions only mark request state); the fail-secure 403 is fully formed.
   */
  public suspend fun handlePassthrough(request: GuardRequest): GuardResponse? =
      bypassHandler.handlePassthrough(request) { null }

  /**
   * Python `_handle_security_bypass` (middleware.py:557): the route-level all-checks bypass. The
   * downstream Ktor handler runs AFTER this hook (suspend-native dispatch), so the call-next
   * response is materialized at the response phase: the non-passive branch marks the request state
   * and the plugin applies the modifier when the response is ready. Observable behavior matches the
   * reference: the bypass event fires and the bypassed response passes through
   * custom_response_modifier.
   */
  public suspend fun handleSecurityBypass(
      request: GuardRequest,
      routeConfig: RouteConfig?,
  ): GuardResponse? =
      bypassHandler.handleSecurityBypass(
          request,
          { _ ->
            request.state.setAttr(GUARD_BYPASS_ATTR, true)
            null
          },
          routeConfig,
      )

  /** Python `_handle_pipeline_block` (middleware.py:573). */
  public suspend fun handlePipelineBlock(request: GuardRequest): GuardResponse? =
      securityPipeline?.execute(request)

  /** Python dispatch usage-rules step (middleware.py:599-601). */
  public suspend fun processUsageRules(
      request: GuardRequest,
      clientIp: String,
      routeConfig: RouteConfig,
  ) {
    behavioralProcessor.processUsageRules(request, clientIp, routeConfig)
  }

  /** Python `_process_response` (middleware.py:482). */
  public suspend fun processResponse(
      request: GuardRequest,
      response: GuardResponse,
      responseTime: Double,
      routeConfig: RouteConfig?,
  ): GuardResponse =
      errorResponseFactory.processResponse(
          request,
          response,
          responseTime,
          routeConfig,
          processBehavioralRules =
              if (routeConfig != null) {
                { req, res, ip, rc -> behavioralProcessor.processReturnRules(req, res, ip, rc) }
              } else {
                null
              },
          processGlobalBehavioralRules = { req, res, ip, rules ->
            behavioralProcessor.processGlobalReturnRules(req, res, ip, rules)
          },
      )

  /** Python `_inject_cors_headers` (middleware.py:508): CorsHandler response headers. */
  public fun injectCorsHeaders(response: GuardResponse, requestHeaders: Map<String, String>) {
    val cors = corsHandler ?: return
    for ((name, value) in cors.buildResponseHeaders(requestHeaders)) {
      response.headers[name] = value
    }
  }

  /** Python `_redis_unavailable_response` (middleware.py:727). */
  public suspend fun redisUnavailableResponse(): GuardResponse {
    val response = createErrorResponse(503, "Service temporarily unavailable")
    response.headers["Retry-After"] = "5"
    return response
  }

  // ----------------------------------------------------------- protocol surface

  /** Python `create_error_response` (middleware.py:735). */
  override suspend fun createErrorResponse(statusCode: Int, defaultMessage: String): GuardResponse =
      errorResponseFactory.createErrorResponse(statusCode, defaultMessage)

  /** Python `refresh_cloud_ip_ranges` (middleware.py:738). */
  override suspend fun refreshCloudIpRanges() {
    val providers = config.blockCloudProviders
    if (providers.isNullOrEmpty()) {
      return
    }
    val cloud = CloudManager.getInstance()
    val providerNames = providers.map { it.toString() }.toSet()
    if (config.enableRedis && redisManager != null) {
      cloud.initializeCloudRedis(redisManager, providerNames, ttl = config.cloudIpRefreshInterval)
    } else {
      cloud.refresh(providerNames)
    }
    lastCloudIpRefresh = (System.currentTimeMillis() / 1000).toInt()
  }

  /** Python `reset` (middleware.py:741). */
  public suspend fun reset() {
    rateLimitManager.reset()
  }

  /** Python `get_initialization_status` (middleware.py:745). */
  public fun getInitializationStatus(): Map<String, Any?> =
      handlerInitializer.getInitializationStatus()

  /** Python `agent_stats` property (middleware.py:313). */
  public fun agentStats(): Map<String, Any?> =
      if (agentHandlerValue == null || agentHandlerValue is CompositeAgentProtocol) {
        linkedMapOf("enabled" to (agentHandlerValue != null), "degraded" to agentDegraded)
      } else {
        val stats = linkedMapOf<String, Any?>("enabled" to true, "degraded" to agentDegraded)
        stats
      }

  /** The plugin's ApplicationStopped hook calls this (engine shutdown). */
  public suspend fun shutdown() {
    handlerInitializer.shutdownAgentIntegrations()
    redisManager?.close()
  }

  // ----------------------------------------------------------- helpers

  /**
   * The composite handler as the agent protocol (python passes the composite as agent_handler; the
   * HandlerInitializer builds the same adapter shape over the composite).
   */
  private class CompositeAgentProtocol(
      private val composite: CompositeAgentHandler,
  ) : AgentHandlerProtocol {
    override suspend fun initializeRedis(redisHandler: RedisHandlerProtocol) {
      composite.initializeRedis(redisHandler)
    }

    override suspend fun sendEvent(event: Any?) {
      @Suppress("UNCHECKED_CAST")
      composite.sendEvent((event as? Map<String, Any?>) ?: linkedMapOf())
    }

    override suspend fun sendMetric(metric: Any?) {
      @Suppress("UNCHECKED_CAST")
      composite.sendMetric((metric as? Map<String, Any?>) ?: linkedMapOf())
    }

    override suspend fun start() {
      composite.start()
    }

    override suspend fun stop() {
      composite.stop()
    }

    override suspend fun flushBuffer() {
      composite.flushBuffer()
    }

    override suspend fun getDynamicRules(): Any? = composite.getDynamicRules()

    override suspend fun healthCheck(): Boolean = composite.healthCheck()
  }
}

/**
 * The [MiddlewareResponseFactoryProtocol] seam: createResponse/createRedirectResponse delegate to
 * the Ktor factory; createHttpsRedirect/applyModifier delegate to the facade [ErrorResponseFactory]
 * (factory.py:34/68 call shapes).
 */
internal class AdapterResponseFactory(
    private val middleware: GuardCoreMiddleware,
) : MiddlewareResponseFactoryProtocol {
  private val ktorFactory = KtorGuardResponseFactory()

  override fun createResponse(content: Any?, statusCode: Int): GuardResponse {
    val body = content as? String ?: content?.toString() ?: ""
    return ktorFactory.createResponse(body, statusCode)
  }

  override fun createRedirectResponse(url: Any?, statusCode: Int): GuardResponse =
      ktorFactory.createRedirectResponse(url.toString(), statusCode)

  override suspend fun createHttpsRedirect(request: GuardRequest): GuardResponse =
      middleware.errorResponseFactory.createHttpsRedirect(request)

  override suspend fun applyModifier(response: GuardResponse): GuardResponse =
      middleware.errorResponseFactory.applyModifier(response)
}
