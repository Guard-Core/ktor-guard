<p align="center">
    <a href="https://guard-core.github.io/guard-core/latest/">
        <img src="https://guard-core.github.io/guard-core/latest/assets/guard_core_legend.svg" alt="Guard Core">
    </a>
</p>

___

<p align="center">
    <strong>Ktor adapter for the [guard-core](https://github.com/Guard-Core/guard-core-jvm) engine for the JVM.</strong>
</p>

<p align="center">
    <a href="https://github.com/Guard-Core/ktor-guard/actions/workflows/ci.yml">
        <img src="https://github.com/Guard-Core/ktor-guard/actions/workflows/ci.yml/badge.svg" alt="CI">
    </a>
    <a href="https://github.com/Guard-Core/ktor-guard/actions/workflows/code-ql.yml">
        <img src="https://github.com/Guard-Core/ktor-guard/actions/workflows/code-ql.yml/badge.svg" alt="CodeQL">
    </a>
    <a href="https://opensource.org/licenses/MIT">
        <img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License">
    </a>
</p>

___

## Status

`1.1.1`, released on Maven Central as `com.guard-core:ktor-guard:1.1.1`. ktor-guard is a true port of
the guard adapter pattern (fastapi-guard's `SecurityMiddleware`, JVM-idiomized for Ktor 3.x):
it wraps Ktor `ApplicationCall` into the guard-core-jvm facade protocols and drives the engine's
security pipeline per request through suspend-native Ktor server plugin hooks. It contains no
security logic of its own; every decision is the engine's.

- Engine: `com.guard-core:guard-core:4.3.3` (built from source through a composite build, see
  [Engine dependency](#engine-dependency)).
- Ktor 3.x (built and tested against 3.6.0), Kotlin 2.4.21, JDK 17, suspend-native: the facade's
  suspend APIs are called directly (no runBlocking bridges on this adapter).
- Tests: real Ktor `testApplication` suites covering the pipeline, byte-exact facade error
  shapes, bypass, per-route decorator configs, CORS, HTTPS enforcement, the Redis-backed rate
  limit, and the bounded body-read contract.

## Install

Published on Maven Central:

```kotlin
dependencies {
    implementation("com.guard-core:ktor-guard:1.1.1")
}
```

It brings in the engine facade `com.guard-core:guard-core:4.3.3`. To build from source, see [Engine dependency](#engine-dependency) and [Development](#development).

## Usage

Install the plugin with an engine `SecurityConfig` and wire per-route configuration through a
`GuardRouteDecorator`:

```kotlin
import com.guardcore.ktor.GuardCore
import com.guardcore.ktor.GuardRouteDecorator
import com.guardcore.ktor.SecurityConfigBuilder
import com.guardcore.ktor.guard
import com.guardcore.ktor.guardRateLimit
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer

fun main() {
    val config = SecurityConfigBuilder().apply {
        blockedUserAgents = listOf("badbot")
        enableRateLimiting = true
    }.build()

    val guard = GuardRouteDecorator(config)

    embeddedServer(CIO, port = 8080) {
        install(GuardCore) {
            this.config = config
            this.decorator = guard
        }
        routing {
            get("/public") { call.respondText("hello") }
            get("/limited") {
                call.respondText("limited")
            }.guardRateLimit(guard, requests = 5, window = 60)
            get("/admin") {
                call.respondText("admin")
            }.guard(guard) {
                requireIp(whitelist = listOf("10.0.0.1"))
            }
        }
    }.start(wait = true)
}
```

A runnable version lives in [examples/demo-ktor](examples/demo-ktor).

### What the plugin does per request

The dispatch chain mirrors the reference adapter exactly:

1. Lazy single-flight initialization; a Redis failure at init answers `503 Service temporarily
   unavailable` with `Retry-After: 5`.
2. The call wraps into the facade's `GuardRequest` (headers, method, path, query, client IP,
   bounded body reads with replay to the handler).
3. CORS preflight runs the pipeline and answers with the engine's preflight response.
4. Path exclusions pass through (fail-secure 403 when no client address resolves under
   `fail_secure`).
5. The client IP resolves through the engine (`trusted_proxies`, `X-Forwarded-For` chain,
   `X-Forwarded-Proto` honoring).
6. Route configuration resolves per request from the stamped route id.
7. Route-level all-checks bypass: the event fires and the response passes through the custom
   response modifier.
8. The 17-check fixed-order pipeline runs; a block short-circuits the route handler and responds
   with the engine's byte-exact error shape.
9. Behavioral usage rules run for decorated routes.
10. The response exit path runs: metrics, security headers, CORS headers, behavioral return
    rules, and the custom response modifier.

A block responds through the facade's error-response factory (status, body, and security headers
are the engine's), and the route handler never executes.

### Decorator surface

`GuardRouteDecorator` carries the facade `SecurityDecorator` factories 1:1 (same parameter
surface, route key bound by the extension). Grouped blocks mirror the Python mixins; every
factory is also a direct route extension:

- `guardAccessControl { requireIp(...) / blockCountries(...) / allowCountries(...) /
  blockClouds(...) / bypass(...) }` and `guardRequireIp`, `guardBlockCountries`,
  `guardAllowCountries`, `guardBlockClouds`, `guardBypass`
- `guardRateLimiting { rateLimit(...) / geoRateLimit(...) }` and `guardRateLimit`,
  `guardGeoRateLimit`
- `guardBehavioral { usageMonitor(...) / returnMonitor(...) / behaviorAnalysis(...) /
  suspiciousFrequency(...) }` and `guardUsageMonitor`, `guardReturnMonitor`,
  `guardBehaviorAnalysis`, `guardSuspiciousFrequency`
- `guardAuthentication { requireHttps() / requireAuth(...) / apiKeyAuth(...) /
  requireAuthorizationHeader(...) / requireHeaders(...) }` and `guardRequireHttps`,
  `guardRequireAuth`, `guardApiKeyAuth`, `guardRequireAuthorizationHeader`, `guardRequireHeaders`
- `guardContentFiltering { blockUserAgents(...) / contentTypeFilter(...) / maxRequestSize(...) /
  requireReferrer(...) / customValidation(...) / detectionExclusion(...) }` and
  `guardBlockUserAgents`, `guardContentTypeFilter`, `guardMaxRequestSize`, `guardRequireReferrer`,
  `guardCustomValidation`, `guardDetectionExclusion`
- `guardAdvanced { timeWindow(...) / suspiciousDetection(...) / honeypotDetection(...) }` and
  `guardTimeWindow`, `guardSuspiciousDetection`, `guardHoneypotDetection`
- `guard(decorator) { ... }` applies any mix through one bound surface.

Stacked groups on one route mutate the same `RouteConfig` (one stamp per route, the TS WeakMap
analog), and the pipeline rebuilds when route configs change.

### Plugin configuration

| Option | Type | Meaning |
|---|---|---|
| `config` | `SecurityConfig` | The engine configuration (required). |
| `decorator` | `GuardRouteDecorator?` | The per-route configuration surface adopted by the middleware. |
| `agentHandler` | `AgentHandlerProtocol?` | guard-agent-jvm handler: pass `GuardAgentTelemetry(agent)` from `com.guard-core:guard-agent-jvm` (see its README). Without one, `enable_agent` degrades like the reference adapter's missing-package path. |
| `connectingIpResolver` | `((ApplicationCall) -> String?)?` | Optional connecting-IP hook (defaults to the engine connection point's remote host). |

The middleware handle is reachable with `application.guardCore().middleware`:
`reset()`, `refreshCloudIpRanges()`, `createErrorResponse(...)`, `getInitializationStatus()`,
`agentStats()`, and `shutdown()` mirror the reference adapter's guard surface.

## Engine dependency

ktor-guard contains no security logic; the engine lives in
[Guard-Core/guard-core-jvm](https://github.com/Guard-Core/guard-core-jvm) and is built from
source through a composite build (`includeBuild` + dependency substitution in
`settings.gradle.kts`, the Gradle analog of the Rust path+version pattern). Declared coordinates
stay `com.guard-core:guard-core:4.3.3` (lockstep with the guard-core 4.3.3 release); substitution
redirects them to the included build until the artifacts publish to Maven Central.

CI checks the engine out at the pinned ref as a sibling (`path: guard-core-jvm`); locally use
the same layout:

```bash
git clone https://github.com/Guard-Core/guard-core-jvm ../guard-core-jvm
./gradlew build --no-daemon
```

Set `GUARD_CORE_USE_COMPOSITE=false` to build without the composite: the declared coordinates
then resolve from the repositories (mavenLocal first, then Maven Central) - this is how the
release workflow proves the published artifact graph (docs/release.md).

Adapter parity with the reference middleware releases is assessed per upstream feature release:
[docs/adapter-parity-8.1.0.md](docs/adapter-parity-8.1.0.md) maps fastapi-guard 8.1.0's
`PureASGISecurityMiddleware` onto this adapter's Ktor server-plugin hooks (all three
pure-streaming properties hold natively; zero code changes).

## Development

```bash
./gradlew build --no-daemon            # compile + ktfmt check + detekt + tests
./gradlew test --no-daemon             # tests (Redis-backed cases need localhost:6379; they skip otherwise)
./gradlew spotlessApply --no-daemon    # format
```

The engine and the facade are external here; adapter work never touches security logic. Facade
bugs go through guard-core-jvm's own PR process.

## License

[MIT](LICENSE)
