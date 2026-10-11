# API

The adapter's public surface (package `com.guardcore.ktor`). Consumers import
from the adapter package, never from the engine coordinates directly.

## GuardCore (the server plugin)

`install(GuardCore)` at the Plugins phase gives the required short-circuit: a
pipeline block responds with the engine's byte-exact error shape and the route
handler never executes (proven by a dedicated test). Configuration:

```kotlin
install(GuardCore) {
    config = SecurityConfigBuilder().apply { /* ... */ }.build()
    decorator = GuardRouteDecorator(config)   // optional per-route config
}
```

Per-request behavior (mirroring the reference adapter exactly): lazy
single-flight init with the `503` + `Retry-After: 5` Redis path; the call
wraps into the facade `GuardRequest` (bounded body reads with replay to the
handler); CORS preflight; path exclusions (fail-secure 403 when no client
address resolves under `fail_secure`); client-IP resolution through the
engine (`trusted_proxies`, `X-Forwarded-For` chain, `X-Forwarded-Proto`);
route configuration resolves per request from the stamped route id; the
17-check fixed-order pipeline; behavioral usage rules for decorated routes;
and the response exit path (metrics, security headers, CORS headers,
behavioral return rules, custom response modifier).

Eager init: `install(GuardCore)` initializes on `ServerReady` with a
single-flight mutex.

## GuardRouteDecorator

`GuardRouteDecorator` carries the facade `SecurityDecorator` factories 1:1
(same parameter surface, route key bound by the extension). Grouped blocks
mirror the Python mixins:

```kotlin
get("/limited") { call.respondText("limited") }
    .guardRateLimit(guard, requests = 5, window = 60)

get("/admin") { call.respondText("admin") }
    .guard(guard) {
        requireIp(whitelist = listOf("10.0.0.1"))
    }
```

25/25 decorator factories are available, mirroring the facade class exactly.

## Re-exports

`GuardCoreExports.kt` re-exports the engine's user-facing types (the
fastapi-guard `guard/__init__.py` convention): `SecurityConfig`,
`SecurityConfigBuilder`, `SecurityDecorator`, `RouteConfig`, the handler
managers, and the protocol types.

## Response handling

The response side hooks `ResponseBodyReadyForSend`: the buffered mode is the
exact Starlette replacement semantics (byte-exact header/body shaping);
streaming responses are additions only (headers + behavioral scan where the
bounded reader yields a prefix).
