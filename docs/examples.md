# Examples

`examples/demo-ktor` is a runnable Ktor server with guarded and open routes,
live Redis-backed per-route rate limiting, and the handler-ran-never proof
for blocked requests.

```bash
git clone https://github.com/Guard-Core/ktor-guard.git
cd ktor-guard
git clone https://github.com/Guard-Core/guard-core-jvm.git guard-core-jvm
git -C guard-core-jvm checkout 6cde4af
docker run -d -p 6379:6379 redis:7-alpine
./gradlew :examples:demo-ktor:run
```

Minimal integration in your own app:

```kotlin
import com.guardcore.ktor.GuardCore
import com.guardcore.ktor.GuardRouteDecorator
import com.guardcore.ktor.SecurityConfigBuilder
import com.guardcore.ktor.guard
import com.guardcore.ktor.guardRateLimit
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

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
            get("/limited") { call.respondText("limited") }
                .guardRateLimit(guard, requests = 5, window = 60)
        }
    }.start(wait = true)
}
```

See [API](api.md) for the full plugin behavior and decorator surface.
