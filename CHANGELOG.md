# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Unreleased

### Changed

- The pinned guard-core-jvm engine ref moves to 9067b7b, which fixes the agent telemetry wiring (Guard-Core/guard-core-jvm#48): an injected guard-agent-jvm handler was never added to the engine composite, so it was never started and received no events, and metrics travelled as events. A new `InjectedAgentWiringTest` covers the injected handler end to end (start, events, metrics, stop).
- Dependencies: netty is pinned to 4.1.139.Final through `platform(io.netty:netty-bom)`. lettuce-core 6.8.2 declares netty 4.1.125, which carries twelve advisories fixed by 4.1.137 at the latest. The pin is declared here as well as in the guard-core-jvm facade, so this lockfile stays the same whichever engine revision the build resolves. All twelve netty osv-scanner suppressions are removed.
- Two stale code comments that said the guard-agent-jvm artifact "ships in W7" now describe the injected `agentHandler` (`GuardCorePlugin.kt`, `GuardCoreMiddleware.kt`).

## [1.0.0] - 2026-10-13

### Changed (release train)

- First Maven Central release (`com.guard-core:ktor-guard:1.0.0`) against guard-core-jvm 4.3.3.
- Dependencies: the facade's lettuce-core moves to 6.8.2.RELEASE (netty 4.1.125) and slf4j-api to 2.0.20, lockfile regenerated; the osv-scanner suppression GHSA-3p8m-j85q-pgmj is removed (fixed in netty 4.1.125).
- Dependency locking ignores the family's own `com.guard-core:*` artifacts. The release publish job resolves the engine from Maven Central instead of the composite build, and those modules were absent from the lock state, so the publish build failed lock validation.
- The pinned guard-core-jvm engine ref moves to the 4.3.3 release commit.

### Changed (W10, the 8.1.0 parity assessment + the engine-pin sync; nothing released)

- fastapi-guard 8.1.0 parity: the PureASGI idiom maps to this adapter's native Ktor server-plugin hooks (the Plugins-phase `Hook` with `finish()` short-circuit on the request side, `ResponseBodyReadyForSend` on the response side); assessment in docs/adapter-parity-8.1.0.md. Verdict: all three pure-streaming properties hold natively (no downstream wrap, streamed bodies get additions only, replaying cached receive); zero code changes.
- The lockstep engine pin moves with the train: `guardCore` 4.3.2 -> 4.3.3 (gradle/libs.versions.toml + the coordinate mentions), mirroring the guard-core-jvm 4.3.3 parity release. Composite builds substitute the included checkout; the declared coordinate matters only at composite-OFF release time, when the engine 4.3.3 artifacts will be live (the pre-flight gate enforces engine-first).

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
