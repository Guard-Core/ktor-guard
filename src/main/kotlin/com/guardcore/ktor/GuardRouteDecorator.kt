package com.guardcore.ktor

import com.guardcore.config.SecurityConfig
import com.guardcore.decorators.RouteAuthVerifier
import com.guardcore.decorators.SecurityDecorator
import com.guardcore.decorators.StampedRoute
import com.guardcore.handlers.BehaviorRule
import com.guardcore.protocols.GuardRequest
import io.ktor.http.HttpMethod
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.path
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes

/**
 * The Ktor decorator surface: the user-held per-route configuration object (the Python family's
 * `GuardDecorator(config)` idiom), carrying the facade [SecurityDecorator]'s 25 factories 1:1 and
 * stamping route ids onto Ktor routes.
 *
 * The Python idiom stacks factories above a handler; the factory closes over the args and mutates
 * the handler's RouteConfig through `_ensure_route_config`. The Ktor idiom applies the same factory
 * to a [Route]; the route key is a [GuardRouteStamp] cached on the route's attributes (the TS
 * WeakMap analog: one stamp per route, so stacked factories mutate the SAME RouteConfig), and the
 * stamped route id is read back at request time by the GuardCore plugin.
 *
 * Usage:
 * ```kotlin
 * val guard = GuardRouteDecorator(config)
 * routing {
 *     get("/admin") { call.respondText("secret") }
 *         .guard(guard) {
 *             rateLimit(requests = 5, window = 60)
 *             requireIp(whitelist = listOf("10.0.0.1"))
 *         }
 * }
 * ```
 *
 * File anchors: the facade decorators/SecurityDecorator.kt (access_control.py, rate_limiting.py,
 * behavioral.py, authentication.py, content_filtering.py, advanced.py mixins).
 */
public class GuardRouteDecorator(
    config: SecurityConfig,
) : SecurityDecorator(config)

/** A callable identity carrying the stamped route id (the `_guard_route_id` carrier). */
public class GuardRouteStamp
internal constructor(
    override val routeKey: String,
) : StampedRoute {
  override var stampedRouteId: String? = null
}

private val GUARD_ROUTE_STAMP = AttributeKey<GuardRouteStamp>("GuardRouteStamp")
private val GUARD_ROUTE_ID = AttributeKey<String>("GuardRouteId")

/** The attribute the GuardCore plugin reads the stamped route id from. */
public val GuardRouteIdAttribute: AttributeKey<String> = GUARD_ROUTE_ID

/**
 * Returns (allocating once) the route's stamp: the same [GuardRouteStamp] instance every factory on
 * this route shares, so stacked factories mutate one RouteConfig.
 */
internal fun Route.guardStamp(): GuardRouteStamp =
    attributes.getOrPutAttr(GUARD_ROUTE_STAMP) {
      GuardRouteStamp(describeRoute())
    }

/**
 * Human-readable route key (the module.qualname analog): the framework's own path rendering with
 * the method selector, e.g. `GET:/api/users/{id}`. Uniqueness beyond this description is the
 * facade's concern (BaseSecurityDecorator allocates `key#2`-style ids on collision).
 */
private fun Route.describeRoute(): String {
  val method = methodSelectorOf(this)
  return buildString {
    if (method != null) {
      append(method.value)
      append(":")
    }
    append(path())
  }
}

/** The nearest [HttpMethodRouteSelector] on the route chain, or null. */
internal fun methodSelectorOf(route: Route): HttpMethod? {
  var node: Route? = route
  while (node != null) {
    (node.selector as? HttpMethodRouteSelector)?.let {
      return it.method
    }
    node = node.parent
  }
  return null
}

/** [Attributes.getOrPut] helper (Ktor ships no equivalent). */
internal fun <T : Any> Attributes.getOrPutAttr(key: AttributeKey<T>, init: () -> T): T =
    getOrNull(key) ?: init().also { put(key, it) }

/** Reads the stamped route id for a matched [Route], or null when the route carries none. */
internal fun routeIdOf(route: Route?): String? = route?.attributes?.getOrNull(GUARD_ROUTE_ID)

private fun Route.applyStamp(stamp: GuardRouteStamp): Route {
  stamp.stampedRouteId?.let { attributes.put(GUARD_ROUTE_ID, it) }
  return this
}

// -------------------------------------------------------------------------
// Bound factory receivers: the block receivers the route extensions hand out,
// one per python mixin, delegating to the facade decorator with the route stamp
// bound (facade factories take the route key first; the binding hides it).
// -------------------------------------------------------------------------

/** Bound surface for access_control.py factories (AccessControlMixin). */
public class GuardRouteAccessControl
internal constructor(
    private val decorator: GuardRouteDecorator,
    private val stamp: GuardRouteStamp,
) {
  /** Python `require_ip` (access_control.py:16). */
  public fun requireIp(whitelist: List<String>? = null, blacklist: List<String>? = null) {
    decorator.requireIp(stamp, whitelist, blacklist)
  }

  /** Python `block_countries` (access_control.py:30). */
  public fun blockCountries(countries: List<String>) {
    decorator.blockCountries(stamp, countries)
  }

  /** Python `allow_countries` (access_control.py:42). */
  public fun allowCountries(countries: List<String>) {
    decorator.allowCountries(stamp, countries)
  }

  /** Python `block_clouds` (access_control.py:54). */
  public fun blockClouds(providers: List<String>? = null) {
    decorator.blockClouds(stamp, providers)
  }

  /** Python `bypass` (access_control.py:71). */
  public fun bypass(checks: List<String>) {
    decorator.bypass(stamp, checks)
  }
}

/** Bound surface for rate_limiting.py factories (RateLimitingMixin). */
public class GuardRouteRateLimiting
internal constructor(
    private val decorator: GuardRouteDecorator,
    private val stamp: GuardRouteStamp,
) {
  /** Python `rate_limit` (rate_limiting.py:11). */
  public fun rateLimit(requests: Int, window: Int = 60) {
    decorator.rateLimit(stamp, requests, window)
  }

  /** Python `geo_rate_limit` (rate_limiting.py:20). */
  public fun geoRateLimit(limits: Map<String, Pair<Int, Int>>) {
    decorator.geoRateLimit(stamp, limits)
  }
}

/** Bound surface for behavioral.py factories (BehavioralMixin). */
public class GuardRouteBehavioral
internal constructor(
    private val decorator: GuardRouteDecorator,
    private val stamp: GuardRouteStamp,
) {
  /** Python `usage_monitor` (behavioral.py:18). */
  public fun usageMonitor(maxCalls: Int, window: Int = 3600, action: String = "ban") {
    decorator.usageMonitor(stamp, maxCalls, window, action)
  }

  /** Python `return_monitor` (behavioral.py:34). */
  public fun returnMonitor(
      pattern: String,
      maxOccurrences: Int,
      window: Int = 86400,
      action: String = "ban",
  ) {
    decorator.returnMonitor(stamp, pattern, maxOccurrences, window, action)
  }

  /** Python `behavior_analysis` (behavioral.py:52). */
  public fun behaviorAnalysis(rules: List<BehaviorRule>) {
    decorator.behaviorAnalysis(stamp, rules)
  }

  /** Python `suspicious_frequency` (behavioral.py:70). */
  public fun suspiciousFrequency(maxFrequency: Double, window: Int = 300, action: String = "ban") {
    decorator.suspiciousFrequency(stamp, maxFrequency, window, action)
  }
}

/** Bound surface for authentication.py factories (AuthenticationMixin). */
public class GuardRouteAuthentication
internal constructor(
    private val decorator: GuardRouteDecorator,
    private val stamp: GuardRouteStamp,
) {
  /** Python `require_https` (authentication.py:15). */
  public fun requireHttps() {
    decorator.requireHttps(stamp)
  }

  /** Python `require_auth` (authentication.py:22). */
  public fun requireAuth(type: String = "bearer", verifier: RouteAuthVerifier? = null) {
    decorator.requireAuth(stamp, type, verifier)
  }

  /** Python `api_key_auth` (authentication.py:44). */
  public fun apiKeyAuth(headerName: String = "X-API-Key", verifier: RouteAuthVerifier? = null) {
    decorator.apiKeyAuth(stamp, headerName, verifier)
  }

  /** Python `require_authorization_header` (authentication.py:68). */
  public fun requireAuthorizationHeader(scheme: String = "bearer") {
    decorator.requireAuthorizationHeader(stamp, scheme)
  }

  /** Python `require_headers` (authentication.py:82). */
  public fun requireHeaders(headers: Map<String, String>) {
    decorator.requireHeaders(stamp, headers)
  }
}

/** Bound surface for content_filtering.py factories (ContentFilteringMixin). */
public class GuardRouteContentFiltering
internal constructor(
    private val decorator: GuardRouteDecorator,
    private val stamp: GuardRouteStamp,
) {
  /** Python `block_user_agents` (content_filtering.py:17). */
  public fun blockUserAgents(patterns: List<String>) {
    decorator.blockUserAgents(stamp, patterns)
  }

  /** Python `content_type_filter` (content_filtering.py:40). */
  public fun contentTypeFilter(allowedTypes: List<String>) {
    decorator.contentTypeFilter(stamp, allowedTypes)
  }

  /** Python `max_request_size` (content_filtering.py:50). */
  public fun maxRequestSize(sizeBytes: Int) {
    decorator.maxRequestSize(stamp, sizeBytes)
  }

  /** Python `require_referrer` (content_filtering.py:60). */
  public fun requireReferrer(allowedDomains: List<String>) {
    decorator.requireReferrer(stamp, allowedDomains)
  }

  /** Python `custom_validation` (content_filtering.py:70). */
  public fun customValidation(validator: suspend (GuardRequest) -> Any?) {
    decorator.customValidation(stamp, validator)
  }

  /** Python `detection_exclusion` (content_filtering.py:79). */
  public fun detectionExclusion(
      headers: Set<String>? = null,
      params: Set<String>? = null,
      bodyFields: Set<String>? = null,
      categories: Set<String>? = null,
      scanBody: Boolean? = null,
  ) {
    decorator.detectionExclusion(stamp, headers, params, bodyFields, categories, scanBody)
  }
}

/** Bound surface for advanced.py factories (AdvancedMixin). */
public class GuardRouteAdvanced
internal constructor(
    private val decorator: GuardRouteDecorator,
    private val stamp: GuardRouteStamp,
) {
  /** Python `time_window` (advanced.py:52). */
  public fun timeWindow(startTime: String, endTime: String, timezone: String = "UTC") {
    decorator.timeWindow(stamp, startTime, endTime, timezone)
  }

  /** Python `suspicious_detection` (advanced.py:64). */
  public fun suspiciousDetection(enabled: Boolean = true) {
    decorator.suspiciousDetection(stamp, enabled)
  }

  /** Python `honeypot_detection` (advanced.py:72). */
  public fun honeypotDetection(trapFields: List<String>) {
    decorator.honeypotDetection(stamp, trapFields)
  }
}

/** Bound surface over ALL 25 factories for the ungrouped [guard] block. */
public class GuardRouteConfigurator
internal constructor(
    private val decorator: GuardRouteDecorator,
    private val stamp: GuardRouteStamp,
) {
  private val access = GuardRouteAccessControl(decorator, stamp)
  private val rate = GuardRouteRateLimiting(decorator, stamp)
  private val behavioral = GuardRouteBehavioral(decorator, stamp)
  private val auth = GuardRouteAuthentication(decorator, stamp)
  private val content = GuardRouteContentFiltering(decorator, stamp)
  private val advanced = GuardRouteAdvanced(decorator, stamp)

  public fun requireIp(whitelist: List<String>? = null, blacklist: List<String>? = null) =
      access.requireIp(whitelist, blacklist)

  public fun blockCountries(countries: List<String>) = access.blockCountries(countries)

  public fun allowCountries(countries: List<String>) = access.allowCountries(countries)

  public fun blockClouds(providers: List<String>? = null) = access.blockClouds(providers)

  public fun bypass(checks: List<String>) = access.bypass(checks)

  public fun rateLimit(requests: Int, window: Int = 60) = rate.rateLimit(requests, window)

  public fun geoRateLimit(limits: Map<String, Pair<Int, Int>>) = rate.geoRateLimit(limits)

  public fun usageMonitor(maxCalls: Int, window: Int = 3600, action: String = "ban") =
      behavioral.usageMonitor(maxCalls, window, action)

  public fun returnMonitor(
      pattern: String,
      maxOccurrences: Int,
      window: Int = 86400,
      action: String = "ban",
  ) = behavioral.returnMonitor(pattern, maxOccurrences, window, action)

  public fun behaviorAnalysis(rules: List<BehaviorRule>) = behavioral.behaviorAnalysis(rules)

  public fun suspiciousFrequency(maxFrequency: Double, window: Int = 300, action: String = "ban") =
      behavioral.suspiciousFrequency(maxFrequency, window, action)

  public fun requireHttps() = auth.requireHttps()

  public fun requireAuth(type: String = "bearer", verifier: RouteAuthVerifier? = null) =
      auth.requireAuth(type, verifier)

  public fun apiKeyAuth(headerName: String = "X-API-Key", verifier: RouteAuthVerifier? = null) =
      auth.apiKeyAuth(headerName, verifier)

  public fun requireAuthorizationHeader(scheme: String = "bearer") =
      auth.requireAuthorizationHeader(scheme)

  public fun requireHeaders(headers: Map<String, String>) = auth.requireHeaders(headers)

  public fun blockUserAgents(patterns: List<String>) = content.blockUserAgents(patterns)

  public fun contentTypeFilter(allowedTypes: List<String>) = content.contentTypeFilter(allowedTypes)

  public fun maxRequestSize(sizeBytes: Int) = content.maxRequestSize(sizeBytes)

  public fun requireReferrer(allowedDomains: List<String>) = content.requireReferrer(allowedDomains)

  public fun customValidation(validator: suspend (GuardRequest) -> Any?) =
      content.customValidation(validator)

  public fun detectionExclusion(
      headers: Set<String>? = null,
      params: Set<String>? = null,
      bodyFields: Set<String>? = null,
      categories: Set<String>? = null,
      scanBody: Boolean? = null,
  ) = content.detectionExclusion(headers, params, bodyFields, categories, scanBody)

  public fun timeWindow(startTime: String, endTime: String, timezone: String = "UTC") =
      advanced.timeWindow(startTime, endTime, timezone)

  public fun suspiciousDetection(enabled: Boolean = true) = advanced.suspiciousDetection(enabled)

  public fun honeypotDetection(trapFields: List<String>) = advanced.honeypotDetection(trapFields)
}

// -------------------------------------------------------------------------
// Route extensions: the grouping blocks and the 25 per-factory extensions,
// all chainable, all resolving per route through the shared stamp.
// -------------------------------------------------------------------------

/**
 * Applies the access-control factories (require_ip, block_countries, allow_countries, block_clouds,
 * bypass).
 */
public fun Route.guardAccessControl(
    decorator: GuardRouteDecorator,
    block: GuardRouteAccessControl.() -> Unit,
): Route {
  val stamp = guardStamp()
  GuardRouteAccessControl(decorator, stamp).block()
  return applyStamp(stamp)
}

/** Applies the rate-limiting factories (rate_limit, geo_rate_limit). */
public fun Route.guardRateLimiting(
    decorator: GuardRouteDecorator,
    block: GuardRouteRateLimiting.() -> Unit,
): Route {
  val stamp = guardStamp()
  GuardRouteRateLimiting(decorator, stamp).block()
  return applyStamp(stamp)
}

/**
 * Applies the behavioral factories (usage_monitor, return_monitor, behavior_analysis,
 * suspicious_frequency).
 */
public fun Route.guardBehavioral(
    decorator: GuardRouteDecorator,
    block: GuardRouteBehavioral.() -> Unit,
): Route {
  val stamp = guardStamp()
  GuardRouteBehavioral(decorator, stamp).block()
  return applyStamp(stamp)
}

/**
 * Applies the authentication factories (require_https, require_auth, api_key_auth,
 * require_authorization_header, require_headers).
 */
public fun Route.guardAuthentication(
    decorator: GuardRouteDecorator,
    block: GuardRouteAuthentication.() -> Unit,
): Route {
  val stamp = guardStamp()
  GuardRouteAuthentication(decorator, stamp).block()
  return applyStamp(stamp)
}

/**
 * Applies the content-filtering factories (block_user_agents, content_type_filter,
 * max_request_size, require_referrer, custom_validation, detection_exclusion).
 */
public fun Route.guardContentFiltering(
    decorator: GuardRouteDecorator,
    block: GuardRouteContentFiltering.() -> Unit,
): Route {
  val stamp = guardStamp()
  GuardRouteContentFiltering(decorator, stamp).block()
  return applyStamp(stamp)
}

/** Applies the advanced factories (time_window, suspicious_detection, honeypot_detection). */
public fun Route.guardAdvanced(
    decorator: GuardRouteDecorator,
    block: GuardRouteAdvanced.() -> Unit,
): Route {
  val stamp = guardStamp()
  GuardRouteAdvanced(decorator, stamp).block()
  return applyStamp(stamp)
}

/** Applies any mix of factories (the ungrouped form over all 25). */
public fun Route.guard(
    decorator: GuardRouteDecorator,
    block: GuardRouteConfigurator.() -> Unit,
): Route {
  val stamp = guardStamp()
  GuardRouteConfigurator(decorator, stamp).block()
  return applyStamp(stamp)
}

// ------------------------------------------------------- per-factory extensions

/** Python `require_ip` (access_control.py:16). */
public fun Route.guardRequireIp(
    decorator: GuardRouteDecorator,
    whitelist: List<String>? = null,
    blacklist: List<String>? = null,
): Route = guardAccessControl(decorator) { requireIp(whitelist, blacklist) }

/** Python `block_countries` (access_control.py:30). */
public fun Route.guardBlockCountries(
    decorator: GuardRouteDecorator,
    countries: List<String>,
): Route = guardAccessControl(decorator) { blockCountries(countries) }

/** Python `allow_countries` (access_control.py:42). */
public fun Route.guardAllowCountries(
    decorator: GuardRouteDecorator,
    countries: List<String>,
): Route = guardAccessControl(decorator) { allowCountries(countries) }

/** Python `block_clouds` (access_control.py:54). */
public fun Route.guardBlockClouds(
    decorator: GuardRouteDecorator,
    providers: List<String>? = null,
): Route = guardAccessControl(decorator) { blockClouds(providers) }

/** Python `bypass` (access_control.py:71). */
public fun Route.guardBypass(decorator: GuardRouteDecorator, checks: List<String>): Route =
    guardAccessControl(decorator) { bypass(checks) }

/** Python `rate_limit` (rate_limiting.py:11). */
public fun Route.guardRateLimit(
    decorator: GuardRouteDecorator,
    requests: Int,
    window: Int = 60,
): Route = guardRateLimiting(decorator) { rateLimit(requests, window) }

/** Python `geo_rate_limit` (rate_limiting.py:20). */
public fun Route.guardGeoRateLimit(
    decorator: GuardRouteDecorator,
    limits: Map<String, Pair<Int, Int>>,
): Route = guardRateLimiting(decorator) { geoRateLimit(limits) }

/** Python `usage_monitor` (behavioral.py:18). */
public fun Route.guardUsageMonitor(
    decorator: GuardRouteDecorator,
    maxCalls: Int,
    window: Int = 3600,
    action: String = "ban",
): Route = guardBehavioral(decorator) { usageMonitor(maxCalls, window, action) }

/** Python `return_monitor` (behavioral.py:34). */
public fun Route.guardReturnMonitor(
    decorator: GuardRouteDecorator,
    pattern: String,
    maxOccurrences: Int,
    window: Int = 86400,
    action: String = "ban",
): Route = guardBehavioral(decorator) { returnMonitor(pattern, maxOccurrences, window, action) }

/** Python `behavior_analysis` (behavioral.py:52). */
public fun Route.guardBehaviorAnalysis(
    decorator: GuardRouteDecorator,
    rules: List<BehaviorRule>,
): Route = guardBehavioral(decorator) { behaviorAnalysis(rules) }

/** Python `suspicious_frequency` (behavioral.py:70). */
public fun Route.guardSuspiciousFrequency(
    decorator: GuardRouteDecorator,
    maxFrequency: Double,
    window: Int = 300,
    action: String = "ban",
): Route = guardBehavioral(decorator) { suspiciousFrequency(maxFrequency, window, action) }

/** Python `require_https` (authentication.py:15). */
public fun Route.guardRequireHttps(decorator: GuardRouteDecorator): Route =
    guardAuthentication(decorator) { requireHttps() }

/** Python `require_auth` (authentication.py:22). */
public fun Route.guardRequireAuth(
    decorator: GuardRouteDecorator,
    type: String = "bearer",
    verifier: RouteAuthVerifier? = null,
): Route = guardAuthentication(decorator) { requireAuth(type, verifier) }

/** Python `api_key_auth` (authentication.py:44). */
public fun Route.guardApiKeyAuth(
    decorator: GuardRouteDecorator,
    headerName: String = "X-API-Key",
    verifier: RouteAuthVerifier? = null,
): Route = guardAuthentication(decorator) { apiKeyAuth(headerName, verifier) }

/** Python `require_authorization_header` (authentication.py:68). */
public fun Route.guardRequireAuthorizationHeader(
    decorator: GuardRouteDecorator,
    scheme: String = "bearer",
): Route = guardAuthentication(decorator) { requireAuthorizationHeader(scheme) }

/** Python `require_headers` (authentication.py:82). */
public fun Route.guardRequireHeaders(
    decorator: GuardRouteDecorator,
    headers: Map<String, String>,
): Route = guardAuthentication(decorator) { requireHeaders(headers) }

/** Python `block_user_agents` (content_filtering.py:17). */
public fun Route.guardBlockUserAgents(
    decorator: GuardRouteDecorator,
    patterns: List<String>,
): Route = guardContentFiltering(decorator) { blockUserAgents(patterns) }

/** Python `content_type_filter` (content_filtering.py:40). */
public fun Route.guardContentTypeFilter(
    decorator: GuardRouteDecorator,
    allowedTypes: List<String>,
): Route = guardContentFiltering(decorator) { contentTypeFilter(allowedTypes) }

/** Python `max_request_size` (content_filtering.py:50). */
public fun Route.guardMaxRequestSize(decorator: GuardRouteDecorator, sizeBytes: Int): Route =
    guardContentFiltering(decorator) { maxRequestSize(sizeBytes) }

/** Python `require_referrer` (content_filtering.py:60). */
public fun Route.guardRequireReferrer(
    decorator: GuardRouteDecorator,
    allowedDomains: List<String>,
): Route = guardContentFiltering(decorator) { requireReferrer(allowedDomains) }

/** Python `custom_validation` (content_filtering.py:70). */
public fun Route.guardCustomValidation(
    decorator: GuardRouteDecorator,
    validator: suspend (GuardRequest) -> Any?,
): Route = guardContentFiltering(decorator) { customValidation(validator) }

/** Python `detection_exclusion` (content_filtering.py:79). */
public fun Route.guardDetectionExclusion(
    decorator: GuardRouteDecorator,
    headers: Set<String>? = null,
    params: Set<String>? = null,
    bodyFields: Set<String>? = null,
    categories: Set<String>? = null,
    scanBody: Boolean? = null,
): Route =
    guardContentFiltering(decorator) {
      detectionExclusion(headers, params, bodyFields, categories, scanBody)
    }

/** Python `time_window` (advanced.py:52). */
public fun Route.guardTimeWindow(
    decorator: GuardRouteDecorator,
    startTime: String,
    endTime: String,
    timezone: String = "UTC",
): Route = guardAdvanced(decorator) { timeWindow(startTime, endTime, timezone) }

/** Python `suspicious_detection` (advanced.py:64). */
public fun Route.guardSuspiciousDetection(
    decorator: GuardRouteDecorator,
    enabled: Boolean = true,
): Route = guardAdvanced(decorator) { suspiciousDetection(enabled) }

/** Python `honeypot_detection` (advanced.py:72). */
public fun Route.guardHoneypotDetection(
    decorator: GuardRouteDecorator,
    trapFields: List<String>,
): Route = guardAdvanced(decorator) { honeypotDetection(trapFields) }
