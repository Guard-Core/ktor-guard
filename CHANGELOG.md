# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.0-SNAPSHOT] - unreleased

### Added

- W6 adapter implementation (true port of the guard adapter pattern, JVM-idiomized for
  Ktor 3.x; the engine and facade live in guard-core-jvm at pinned ref `5f4ea56`, lockstep
  4.3.2):
  - `GuardCore` server plugin (`createApplicationPlugin`): the full reference dispatch chain
    (lazy single-flight init with the 503 + Retry-After redis-failure response, CORS preflight,
    path-exclusion passthrough, client-IP extraction, route-config resolution, all-checks
    bypass, the 17-check fixed-order pipeline, behavioral usage rules), suspend-native, with a
    block short-circuiting the route handler exactly like the reference adapter's
    return-before-call-next.
  - Protocol bridges: `KtorGuardRequest` (facade `GuardRequest` + `BoundedBodyReader`, joined
    case-insensitive headers, bounded body reads with replay to the handler),
    `KtorGuardResponse` + `KtorGuardResponseFactory` (byte-exact factory shapes, plain-text
    error bodies), and the response exit path (`process_response`: metrics, security headers,
    CORS headers, behavioral return rules, custom response modifier).
  - `GuardRouteDecorator` decorator surface: the facade `SecurityDecorator` factories 1:1
    (25 factories across the access-control, rate-limiting, behavioral, authentication,
    content-filtering, and advanced groups) as chainable Ktor route extensions, resolved
    per request through a per-route stamp (the TS WeakMap analog).
  - Config re-exports from `com.guardcore.ktor` (`SecurityConfig`, `SecurityConfigBuilder`) and
    the `connectingIpResolver` plugin hook (the TS hono adapter's seam).
  - Composite-build engine wiring (`settings.gradle.kts` includeBuild + dependency substitution
    on `com.guardcore:guard-core:4.3.2`) with the pinned engine checkout in CI plus a Redis
    service step for the redis-backed tests.
  - 35 `testApplication` tests (pipeline, byte-exact error shapes, bypass, per-route configs,
    CORS, HTTPS enforcement, redis-backed rate limiting, body-read contract) and the
    `examples/demo-ktor` guarded app.
