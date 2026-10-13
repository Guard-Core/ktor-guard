# Installation

## Requirements

- JDK 17 or newer
- Ktor 3.x (built and tested against 3.6.0)
- Redis 6+ for the distributed features (rate limiting, IP bans, behavioral
  state) - configured on the engine `SecurityConfig` (`redis_url`, default
  `redis://localhost:6379`)

## Coordinates

Published on Maven Central:

```kotlin
dependencies {
    implementation("com.guard-core:ktor-guard:1.0.0")
}
```

The group ID is `com.guard-core` (the Central Portal namespace); Kotlin
packages stay `com.guardcore.*`.

## Engine dependency (includeBuild + dependency substitution)

Consumers resolve the engine artifact `com.guard-core:guard-core` from Maven Central. This repository's own build pins the engine from source instead: CI and
local builds check out `Guard-Core/guard-core-jvm` at the pinned ref `2415a77`
as the sibling directory `./guard-core-jvm`, and `settings.gradle.kts`
substitutes the declared coordinates with the included build's projects. The
declared dependency coordinates stay `com.guard-core:guard-core:4.3.3`
(lockstep version per the family versioning law).

```bash
git clone https://github.com/Guard-Core/guard-core-jvm.git guard-core-jvm
git -C guard-core-jvm checkout 2415a77
./gradlew build
```

The engine directory resolves in order: `-PguardCoreJvmDir=<path>`,
`GUARD_CORE_JVM_DIR`, `./guard-core-jvm`, `../guard-core-jvm`.

Set `GUARD_CORE_USE_COMPOSITE=false` to build without the composite: the
declared coordinates then resolve from the repositories (mavenLocal first,
then Maven Central) - this is how the release workflow proves the published
artifact graph.

## Dependency locking

`gradle.lockfile` is committed and enforced on every resolution: a dependency
change without `./gradlew --write-locks` fails the build. CI additionally
scans the lockfile with osv-scanner (fail on any known vulnerability).
