package com.guardcore.ktor

import com.guardcore.config.SecurityConfig
import com.guardcore.config.SecurityConfigBuilder
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/** The connecting address the Ktor test engine reports; treated as a trusted proxy in tests. */
internal const val TEST_PROXY_HOST: String = "localhost"

/** The client IP the tests forward through the trusted proxy chain. */
internal const val TEST_CLIENT_IP: String = "203.0.113.7"

/** A client IP that is NOT on the test whitelist. */
internal const val TEST_OTHER_IP: String = "198.51.100.5"

/** The connecting IP the test resolver reports (a valid proxy entry for the facade validator). */
internal const val TEST_PROXY_IP: String = "127.0.0.1"

/** Base config: trusted proxy + no redis + no global rate limiting (each test opts in). */
internal fun baseConfig(build: SecurityConfigBuilder.() -> Unit = {}): SecurityConfig =
    SecurityConfigBuilder()
        .apply {
          trustedProxies = listOf(TEST_PROXY_IP)
          // The JVM facade defaults enable_redis/enable_rate_limiting to true; the default
          // redis_url (localhost:6379) would share rate-limit counters across test runs.
          enableRedis = false
          enableRateLimiting = false
        }
        .apply(build)
        .build()

/**
 * Boots a guarded test application: the GuardCore plugin over [testApplication] with the given
 * config and routes, then runs the assertions body.
 */
internal fun guardTest(
    config: SecurityConfig,
    decorator: GuardRouteDecorator? = null,
    routes: Routing.() -> Unit = {},
    assertions: suspend ApplicationTestBuilder.() -> Unit,
): Unit = testApplication {
  resetEngineSingletons()
  application {
    install(GuardCore) {
      this.config = config
      this.decorator = decorator
      // The test engine reports "localhost" as the peer address; pin the resolver to the
      // trusted proxy IP so the X-Forwarded-For chain drives the client IP.
      connectingIpResolver = { _ -> TEST_PROXY_IP }
    }
    routing(routes)
  }
  assertions()
}

/** Resets the facade process-wide manager singletons between test applications. */
internal fun resetEngineSingletons() {
  com.guardcore.handlers.RateLimitManager.resetInstance()
  com.guardcore.handlers.RateLimitState.reset()
  com.guardcore.handlers.IpBanManager.resetInstance()
  com.guardcore.handlers.SecurityHeadersManager.resetInstance()
  com.guardcore.handlers.RedisManager.resetInstance()
}

/** A client that does not follow redirects (the 301 assertions need the raw response). */
internal fun ApplicationTestBuilder.clientNoRedirects(): HttpClient = createClient {
  followRedirects = false
}

// --------------------------------------------------------------- pipeline basics

class GuardCorePluginPipelineTest {
  @Test
  fun cleanRequestsPassThroughThePipeline() =
      guardTest(
          baseConfig(),
          routes = {
            get("/ok") { call.respondText("ok") }
          },
      ) {
        val response = client.get("/ok")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
      }

  @Test
  fun whitelistRejectIsByteExactWithTheFacadeFactory() =
      guardTest(
          baseConfig { whitelist = listOf(TEST_CLIENT_IP) },
          routes = {
            get("/data") { call.respondText("secret") }
          },
      ) {
        val allowed =
            client.get("/data") {
              headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
            }
        assertEquals(HttpStatusCode.OK, allowed.status)

        val blocked: HttpResponse =
            client.get("/data") {
              headers { append(HttpHeaders.XForwardedFor, TEST_OTHER_IP) }
            }
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertEquals("Forbidden", blocked.bodyAsText())
        assertEquals("text/plain; charset=utf-8", blocked.headers[HttpHeaders.ContentType])
        // Security headers ride every factory error response (ErrorResponseFactory).
        assertEquals("nosniff", blocked.headers["X-Content-Type-Options"])
      }

  @Test
  fun blockedRequestsNeverReachTheRouteHandler() =
      guardTest(
          baseConfig { whitelist = listOf(TEST_CLIENT_IP) },
          routes = {
            var handlerRan = false
            get("/guarded") {
              handlerRan = true
              call.respondText("handler")
            }
            get("/status") {
              call.respondText(if (handlerRan) "handler-ran" else "handler-clean")
            }
          },
      ) {
        val blocked =
            client.get("/guarded") {
              headers { append(HttpHeaders.XForwardedFor, TEST_OTHER_IP) }
            }
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        val status =
            client.get("/status") {
              headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
            }
        assertEquals("handler-clean", status.bodyAsText())
      }

  @Test
  fun blacklistedIpIsBlockedWithTheExactBody() =
      guardTest(
          baseConfig { blacklist = listOf(TEST_OTHER_IP) },
          routes = {
            get("/data") { call.respondText("secret") }
          },
      ) {
        val blocked =
            client.get("/data") {
              headers { append(HttpHeaders.XForwardedFor, TEST_OTHER_IP) }
            }
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertEquals("Forbidden", blocked.bodyAsText())
      }

  @Test
  fun penetrationAttemptsInTheRequestLineAreBlocked() =
      guardTest(
          baseConfig(),
          routes = {
            get("/search") { call.respondText("results") }
          },
      ) {
        val response = client.get("/search?q=1%27%20OR%20%271%27%3D%271")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Suspicious activity detected", response.bodyAsText())
      }
}

// --------------------------------------------------------------- lifecycle

class GuardCorePluginLifecycleTest {
  @Test
  fun redisFailureAtInitializationAnswers503WithRetryAfter() =
      guardTest(
          baseConfig {
            enableRedis = true
            redisUrl = "redis://localhost:59999"
            redisFailOpen = false
            redisSocketConnectTimeout = 0.5
          },
          routes = {
            get("/ok") { call.respondText("ok") }
          },
      ) {
        val response = client.get("/ok")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("Service temporarily unavailable", response.bodyAsText())
        assertEquals("5", response.headers[HttpHeaders.RetryAfter])
      }

  @Test
  fun excludedPathsPassThroughWithoutSecurityChecks() =
      guardTest(
          baseConfig {
            excludePaths = listOf("/health")
          },
          routes = {
            get("/health") { call.respondText("healthy") }
          },
      ) {
        // Detection is scoped off on excluded paths: a penetration payload in the request
        // line passes (the suspicious check is not enforced on excluded paths).
        val excluded =
            client.get("/health?q=1%27%20OR%20%271%27%3D%271") {
              headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
            }
        assertEquals(HttpStatusCode.OK, excluded.status)
        assertEquals("healthy", excluded.bodyAsText())

        // The same payload on a regular path blocks with the exact facade body.
        val enforced =
            client.get("/other?q=1%27%20OR%20%271%27%3D%271") {
              headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
            }
        assertEquals(HttpStatusCode.BadRequest, enforced.status)
        assertEquals("Suspicious activity detected", enforced.bodyAsText())
      }

  @Test
  fun passiveModeLogsBlocksButLetsRequestsThrough() =
      guardTest(
          baseConfig {
            whitelist = listOf(TEST_CLIENT_IP)
            passiveMode = true
          },
          routes = {
            get("/data") { call.respondText("data") }
          },
      ) {
        val response =
            client.get("/data") {
              headers { append(HttpHeaders.XForwardedFor, TEST_OTHER_IP) }
            }
        assertEquals(HttpStatusCode.OK, response.status)
      }

  @Test
  fun customResponseModifierRunsOnTheResponseExitPath() =
      guardTest(
          baseConfig {
            customResponseModifier = { response ->
              response.headers["x-guard-mark"] = "modified"
              response
            }
          },
          routes = {
            get("/ok") { call.respondText("ok") }
          },
      ) {
        val response = client.get("/ok")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("modified", response.headers["x-guard-mark"])
      }

  @Test
  fun securityHeadersRideNormalResponses() =
      guardTest(
          baseConfig(),
          routes = {
            get("/ok") { call.respondText("ok") }
          },
      ) {
        val response = client.get("/ok")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("SAMEORIGIN", response.headers["X-Frame-Options"])
      }
}

// --------------------------------------------------------------- https + cors

class GuardCorePluginHttpsCorsTest {
  @Test
  fun enforceHttpsRedirectsHttpToTheHttpsUrlWith301() =
      guardTest(
          baseConfig { enforceHttps = true },
          routes = {
            get("/secure") { call.respondText("secure") }
          },
      ) {
        val http = clientNoRedirects()
        val response = http.get("/secure")
        assertEquals(HttpStatusCode.MovedPermanently, response.status)
        assertEquals("https://localhost/secure", response.headers[HttpHeaders.Location])
      }

  @Test
  fun corsPreflightBuildsThePreflightResponse() =
      guardTest(
          baseConfig {
            enableCors = true
            corsAllowOrigins = listOf("http://allowed.test")
            corsAllowMethods = listOf("GET", "POST")
          },
          routes = {
            get("/cross") { call.respondText("cross") }
          },
      ) {
        val response =
            client.options("/cross") {
              header(HttpHeaders.AccessControlRequestMethod, "GET")
              header(HttpHeaders.Origin, "http://allowed.test")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "http://allowed.test",
            response.headers[HttpHeaders.AccessControlAllowOrigin],
        )
      }

  @Test
  fun corsResponseHeadersRideActualResponses() =
      guardTest(
          baseConfig {
            enableCors = true
            corsAllowOrigins = listOf("http://allowed.test")
          },
          routes = {
            get("/cross") { call.respondText("cross") }
          },
      ) {
        val response =
            client.get("/cross") {
              header(HttpHeaders.Origin, "http://allowed.test")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "http://allowed.test",
            response.headers[HttpHeaders.AccessControlAllowOrigin],
        )
      }
}

// --------------------------------------------------------------- request checks

class GuardCorePluginRequestChecksTest {
  @Test
  fun missingRequiredHeaderIsRejectedWithTheExactBody() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig(),
        decorator = decorator,
        routes = {
          get("/api") { call.respondText("api") }
              .guardRequireHeaders(decorator, headers = mapOf("X-Request-Signature" to "required"))
        },
    ) {
      val missing =
          client.get("/api") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(HttpStatusCode.BadRequest, missing.status)
      assertEquals(
          "Missing required header: X-Request-Signature",
          missing.bodyAsText(),
      )

      val present =
          client.get("/api") {
            headers {
              append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP)
              append("X-Request-Signature", "token")
            }
          }
      assertEquals(HttpStatusCode.OK, present.status)
    }
  }

  @Test
  fun routeAuthRejectsAnonymousAccessWith401() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig(),
        decorator = decorator,
        routes = {
          get("/private") { call.respondText("private") }
              .guardRequireAuth(decorator, type = "bearer")
        },
    ) {
      val response =
          client.get("/private") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals("Authentication required", response.bodyAsText())
    }
  }

  @Test
  fun userAgentBlocklistRejectsMatchingAgents() =
      guardTest(
          baseConfig { blockedUserAgents = listOf("badbot") },
          routes = {
            get("/ok") { call.respondText("ok") }
          },
      ) {
        val blocked =
            client.get("/ok") {
              header(HttpHeaders.UserAgent, "badbot/1.0")
            }
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertEquals("User-Agent not allowed", blocked.bodyAsText())

        val allowed =
            client.get("/ok") {
              header(HttpHeaders.UserAgent, "goodbot/1.0")
            }
        assertEquals(HttpStatusCode.OK, allowed.status)
      }

  @Test
  fun jsonBodiesWithPenetrationPayloadsAreBlocked() =
      guardTest(
          baseConfig(),
          routes = {
            post("/submit") { call.respondText("submitted") }
          },
      ) {
        val response =
            client.post("/submit") {
              headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
              contentType(ContentType.Application.Json)
              setBody("""{"comment": "1' OR '1'='1"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, response.status)
      }
}
