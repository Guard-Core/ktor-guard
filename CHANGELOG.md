# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.0-SNAPSHOT] - unreleased

### Changed (W8b, release engineering; nothing released)

- Maven coordinates move to groupId `com.guard-core` (the Central Portal namespace; guardcore.com
  proved unverifiable and is not owned by the operator; nothing was ever published under
  `com.guardcore`). Kotlin/JVM packages stay `com.guardcore.*` (hyphens are illegal in JVM
  packages).
- `maven-publish` + `signing` with sources/javadoc jars and the Portal-required POM (MIT license,
  developers, scm); repositories live in `settings.gradle.kts`; `GUARD_CORE_USE_COMPOSITE` env
  toggle (default composite ON; `false` = resolve the engine from mavenLocal, then Maven Central,
  the release-workflow mode).
- `.github/workflows/release.yml`: engine-artifact pre-flight on Maven Central (engine before
  adapters), tests at tag, tag-consistency audit (bare vX.Y.z, tag equals the in-file version,
  SNAPSHOT flip enforced), publish job with per-artifact repo1 existence check and a Central
  Portal deployment. See docs/release.md.

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
