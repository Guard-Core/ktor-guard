package com.guardcore.ktor.demo

import com.guardcore.ktor.GuardCore
import com.guardcore.ktor.GuardRouteDecorator
import com.guardcore.ktor.guard
import com.guardcore.ktor.guardRateLimit
import com.guardcore.ktor.guardRequireIp
import com.guardcore.ktor.SecurityConfigBuilder
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * A small guarded Ktor application: the GuardCore plugin runs the engine pipeline on every
 * request, and per-route configs come from the GuardRouteDecorator route extensions.
 *
 * Run (from the repository root):
 * ```
 * git clone https://github.com/Guard-Core/guard-core-jvm ../guard-core-jvm   # engine sibling
 * ./gradlew --no-daemon run -p examples/demo-ktor
 * ```
 * then:
 * - `curl localhost:8080/` passes the pipeline,
 * - `curl -A badbot/1.0 localhost:8080/` gets 403 `User-Agent not allowed`,
 * - `curl localhost:8080/limited` answers 200 twice, then 429 (2 requests / 60s, Redis-backed),
 * - `curl localhost:8080/admin` gets 403 unless the caller IP is on the route whitelist.
 */
fun main() {
    val config = SecurityConfigBuilder().apply {
        blockedUserAgents = listOf("badbot")
        enableRedis = true // rate limiting uses Redis (redis://localhost:6379 by default)
        enableRateLimiting = true
    }.build()

    val guard = GuardRouteDecorator(config)

    embeddedServer(CIO, port = 8080) {
        install(GuardCore) {
            this.config = config
            this.decorator = guard
        }
        routing {
            get("/") {
                call.respondText("public")
            }
            get("/limited") {
                call.respondText("limited")
            }.guardRateLimit(guard, requests = 2, window = 60)
            get("/admin") {
                call.respondText("admin")
            }.guard(guard) {
                requireIp(whitelist = listOf("10.0.0.1"))
            }
        }
    }.start(wait = true)
}
