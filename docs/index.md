# ktor-guard

Ktor 3.x adapter for the [guard-core-jvm](https://github.com/Guard-Core/guard-core-jvm)
engine for the JVM. ktor-guard is a true port of the guard adapter pattern
(fastapi-guard's `SecurityMiddleware`, JVM-idiomized for Ktor 3.x): it wraps
Ktor `ApplicationCall` into the guard-core-jvm facade protocols and drives the
engine's security pipeline per request through suspend-native Ktor server
plugin hooks. It contains no security logic of its own; every decision is the
engine's.

- **Status**: 1.1.1, released
- **Engine**: `com.guard-core:guard-core:4.4.0` (built from source through a
  composite build, see [Installation](installation.md))
- **Stack**: Ktor 3.x (built and tested against 3.6.0), Kotlin 2.4.21, JDK 17,
  suspend-native (the facade's suspend APIs are called directly)
- **Parity**: the engine's
  [every-feature matrix](https://github.com/Guard-Core/guard-core-jvm/blob/master/docs/every-feature-matrix.md)
  and the spec section table carry the family-level status

## Documentation map

- [Installation](installation.md) - coordinates, engine dependency, Redis
- [API](api.md) - the plugin, route decorator, and re-exports
- [Examples](examples.md) - the runnable demo app
