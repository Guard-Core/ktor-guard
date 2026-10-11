package com.guardcore.ktor

import com.guardcore.handlers.RateLimitState
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlin.test.Test
import kotlin.test.assertEquals
import org.opentest4j.TestAbortedException

/**
 * Redis-backed tests: the rate-limit path against a live Redis with the section 08 schema. Skipped
 * (assumption violated) when no Redis answers on localhost:6379; CI pins a Redis service container.
 */
class GuardCoreRedisTest {
  private fun redisAvailable(): Boolean =
      try {
        java.net.Socket("localhost", 6379).use { socket ->
          socket.soTimeout = 2000
          socket.getOutputStream().write("PING\r\n".toByteArray())
          socket.getOutputStream().flush()
          val buf = ByteArray(16)
          val read = socket.getInputStream().read(buf)
          read > 0 && buf.decodeToString(0, read).contains("PONG")
        }
      } catch (_: Exception) {
        false
      }

  private fun flushGuardKeys() {
    // Best-effort local cleanup scoped to the guard_core prefix (CI's Redis is run-scoped).
    try {
      val process =
          ProcessBuilder(
                  "redis-cli",
                  "-h",
                  "localhost",
                  "-p",
                  "6379",
                  "--scan",
                  "--pattern",
                  "guard_core:*",
              )
              .start()
      val keys = process.inputStream.bufferedReader().readLines()
      if (keys.isNotEmpty()) {
        val del =
            ProcessBuilder(
                    "redis-cli",
                    "-h",
                    "localhost",
                    "-p",
                    "6379",
                    "del",
                    *keys.toTypedArray(),
                )
                .start()
        del.waitFor()
      }
    } catch (_: Exception) {}
  }

  @Test
  fun routeRateLimitEnforcesThroughRedis() {
    if (!redisAvailable()) {
      throw TestAbortedException("no Redis on localhost:6379")
    }
    flushGuardKeys()
    resetEngineSingletons()

    val decorator =
        GuardRouteDecorator(
            baseConfig {
              enableRedis = true
              enableRateLimiting = true
            },
        )
    guardTest(
        baseConfig {
          enableRedis = true
          enableRateLimiting = true
        },
        decorator = decorator,
        routes = {
          get("/limited") { call.respondText("limited") }
              .guardRateLimiting(decorator) {
                rateLimit(requests = 2, window = 60)
              }
        },
    ) {
      resetEngineSingletons()
      val first =
          client.get("/limited") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(HttpStatusCode.OK, first.status)

      val second =
          client.get("/limited") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(HttpStatusCode.OK, second.status)

      val third: HttpResponse =
          client.get("/limited") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(HttpStatusCode.TooManyRequests, third.status)
      assertEquals("Too many requests", third.bodyAsText())
      RateLimitState.reset()
    }
  }

  @Test
  fun redisBackedAppServesRequests() {
    if (!redisAvailable()) {
      throw TestAbortedException("no Redis on localhost:6379")
    }
    flushGuardKeys()
    resetEngineSingletons()
    guardTest(
        baseConfig {
          enableRedis = true
          enableRateLimiting = true
        },
        routes = {
          get("/ok") { call.respondText("ok") }
        },
    ) {
      resetEngineSingletons()
      val response =
          client.get("/ok") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("ok", response.bodyAsText())
      RateLimitState.reset()
    }
  }
}
