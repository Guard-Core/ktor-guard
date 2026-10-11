package com.guardcore.ktor

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decorator surface tests: per-route configuration through the GuardRouteDecorator route
 * extensions, resolved per request by the GuardCore plugin.
 */
class GuardRouteDecoratorTest {
  @Test
  fun routeLevelRequireIpOverridesTheOpenApp() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig(),
        decorator = decorator,
        routes = {
          get("/open") { call.respondText("open") }
          get("/strict") { call.respondText("strict") }
              .guardRequireIp(decorator, whitelist = listOf(TEST_CLIENT_IP))
        },
    ) {
      val open =
          client.get("/open") {
            headers { append(HttpHeaders.XForwardedFor, TEST_OTHER_IP) }
          }
      assertEquals(HttpStatusCode.OK, open.status)

      val strictBlocked =
          client.get("/strict") {
            headers { append(HttpHeaders.XForwardedFor, TEST_OTHER_IP) }
          }
      assertEquals(HttpStatusCode.Forbidden, strictBlocked.status)
      assertEquals("Forbidden", strictBlocked.bodyAsText())

      val strictAllowed =
          client.get("/strict") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(HttpStatusCode.OK, strictAllowed.status)
    }
  }

  @Test
  fun stackedFactoriesOnOneRouteMutateTheSameRouteConfig() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig(),
        decorator = decorator,
        routes = {
          get("/combo") { call.respondText("combo") }
              .guardAccessControl(decorator) {
                requireIp(whitelist = listOf(TEST_CLIENT_IP))
              }
              .guardAuthentication(decorator) {
                requireHttps()
              }
        },
    ) {
      // https enforcement comes from the route config, not the global config.
      val blocked =
          clientNoRedirects().get("/combo") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(
          HttpStatusCode.MovedPermanently,
          blocked.status,
          "route-level requireHttps must enforce the redirect, got ${blocked.status}",
      )
    }
  }

  @Test
  fun routeLevelBypassSkipsAllChecksAndRunsTheHandler() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig { blacklist = listOf(TEST_CLIENT_IP) },
        decorator = decorator,
        routes = {
          get("/guarded") { call.respondText("guarded") }
          get("/free") { call.respondText("free") }.guardBypass(decorator, checks = listOf("all"))
        },
    ) {
      val guarded =
          client.get("/guarded") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(HttpStatusCode.Forbidden, guarded.status)

      val bypassed =
          client.get("/free") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(
          HttpStatusCode.OK,
          bypassed.status,
          "bypassed route must skip the pipeline, got ${bypassed.status}",
      )
      assertEquals("free", bypassed.bodyAsText())
    }
  }

  @Test
  fun routeLevelBypassAppliesTheCustomResponseModifier() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig {
          customResponseModifier = { response ->
            response.headers["x-guard-mark"] = "bypass-modified"
            response
          }
        },
        decorator = decorator,
        routes = {
          get("/free") { call.respondText("free") }.guardBypass(decorator, checks = listOf("all"))
        },
    ) {
      val bypassed = client.get("/free")
      assertEquals(HttpStatusCode.OK, bypassed.status)
      assertEquals(
          "bypass-modified",
          bypassed.headers["x-guard-mark"],
          "the bypass response must pass through the modifier; got ${bypassed.headers.names()}",
      )
    }
  }

  @Test
  fun routeLevelUserAgentBlocklistIsRouteScoped() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig(),
        decorator = decorator,
        routes = {
          get("/open") { call.respondText("open") }
          get("/api") { call.respondText("api") }
              .guardBlockUserAgents(decorator, patterns = listOf("badbot"))
        },
    ) {
      val open =
          client.get("/open") {
            header(HttpHeaders.UserAgent, "badbot/1.0")
          }
      assertEquals(HttpStatusCode.OK, open.status)

      val blocked: HttpResponse =
          client.get("/api") {
            header(HttpHeaders.UserAgent, "badbot/1.0")
          }
      assertEquals(HttpStatusCode.Forbidden, blocked.status)

      val allowed =
          client.get("/api") {
            header(HttpHeaders.UserAgent, "goodbot/1.0")
          }
      assertEquals(HttpStatusCode.OK, allowed.status)
    }
  }

  @Test
  fun honeypotDetectionRejectsFilledTrapFields() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig(),
        decorator = decorator,
        routes = {
          post("/contact") { call.respondText("sent") }
              .guardHoneypotDetection(decorator, trapFields = listOf("website"))
        },
    ) {
      val clean =
          client.post("/contact") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("message=hello")
          }
      assertEquals(HttpStatusCode.OK, clean.status)

      val trapped =
          client.post("/contact") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("message=hello&website=spam.example")
          }
      assertEquals(
          HttpStatusCode.Forbidden,
          trapped.status,
          "honeypot trap must reject with 403, got ${trapped.status}",
      )
      assertEquals("Forbidden", trapped.bodyAsText())
    }
  }

  @Test
  fun customValidationReceivesTheFacadeRequest() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig(),
        decorator = decorator,
        routes = {
          get("/checked") { call.respondText("checked") }
              .guardCustomValidation(decorator) { request ->
                if ("forbidden-token" == request.headers["x-custom"]) {
                  KtorGuardResponse(403, "custom denial")
                } else {
                  null
                }
              }
        },
    ) {
      val allowed =
          client.get("/checked") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(HttpStatusCode.OK, allowed.status)

      val denied =
          client.get("/checked") {
            headers {
              append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP)
              append("X-Custom", "forbidden-token")
            }
          }
      assertEquals(HttpStatusCode.Forbidden, denied.status)
      assertEquals("custom denial", denied.bodyAsText())
    }
  }

  @Test
  fun requireAuthRejectsAnonymousAccess() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig(),
        decorator = decorator,
        routes = {
          get("/private") { call.respondText("private") }
              .guardRequireAuth(decorator, type = "bearer")
        },
    ) {
      val anonymous =
          client.get("/private") {
            headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) }
          }
      assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
      assertEquals("Authentication required", anonymous.bodyAsText())
    }
  }

  @Test
  fun routeStampsResolveTheDecoratedRouteConfig() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig(),
        decorator = decorator,
        routes = {
          get("/stamped") { call.respondText("stamped") }
              .guardRateLimiting(decorator) {
                rateLimit(requests = 1000, window = 60)
              }
        },
    ) {
      // A request boots the app and builds the route tree; the stamp then resolves.
      client.get("/stamped")
      // The decorator registered exactly one route config for the stamped route.
      assertEquals(1, decorator.routeConfigMap.size)
      val routeConfig = decorator.routeConfigMap.values.first()
      assertNotNull(routeConfig)
      assertEquals(1000, routeConfig.rateLimit)
      assertEquals(60, routeConfig.rateLimitWindow)
    }
  }

  @Test
  fun everyGroupingBlockExposesItsFactories() {
    val decorator = GuardRouteDecorator(baseConfig())
    guardTest(
        baseConfig(),
        decorator = decorator,
        routes = { applyAllGroups(decorator) },
    ) {
      val http = clientNoRedirects()
      for (path in listOf("/a", "/b", "/c", "/d", "/f", "/g")) {
        val response =
            http.get(path) {
              headers {
                append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP)
                append("X-Extra", "required")
                append(HttpHeaders.Authorization, "bearer")
                append(HttpHeaders.Referrer, "http://allowed.test/page")
              }
            }
        assertTrue(
            response.status.value in listOf(200, 301),
            "route $path expected 200/301, got ${response.status}: ${response.bodyAsText()}",
        )
      }
      // /e is a POST route gated by the content-type filter.
      val e =
          http.post("/e") {
            headers {
              append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP)
              append(HttpHeaders.Referrer, "http://allowed.test/page")
            }
            contentType(ContentType.Application.Json)
            setBody("{}")
          }
      assertEquals(HttpStatusCode.OK, e.status, "route /e expected 200, got ${e.status}")
    }
  }

  /** Applies every grouping block to its route (the compile-level surface check). */
  private fun Routing.applyAllGroups(decorator: GuardRouteDecorator) {
    get("/a") { call.respondText("a") }
        .guardAccessControl(decorator) {
          requireIp(whitelist = listOf(TEST_CLIENT_IP))
          blockCountries(listOf("KP"))
          allowCountries(listOf("US"))
          blockClouds(listOf("AWS"))
          bypass(listOf("ip_ban"))
        }
    get("/b") { call.respondText("b") }
        .guardRateLimiting(decorator) {
          rateLimit(requests = 10, window = 60)
          geoRateLimit(mapOf("US" to Pair(100, 60)))
        }
    get("/c") { call.respondText("c") }
        .guardBehavioral(decorator) {
          usageMonitor(maxCalls = 100)
          suspiciousFrequency(maxFrequency = 1.0)
        }
    get("/d") { call.respondText("d") }
        .guardAuthentication(decorator) {
          requireHttps()
          requireHeaders(mapOf("X-Extra" to "required"))
          requireAuthorizationHeader()
        }
    post("/e") { call.respondText("e") }
        .guardContentFiltering(decorator) {
          contentTypeFilter(listOf("application/json"))
          maxRequestSize(1024)
          requireReferrer(listOf("allowed.test"))
          detectionExclusion(headers = setOf("x-noise"))
        }
    get("/f") { call.respondText("f") }
        .guardAdvanced(decorator) {
          timeWindow(startTime = "00:00", endTime = "23:59")
          suspiciousDetection(enabled = true)
        }
    get("/g") { call.respondText("g") }
        .guard(decorator) {
          requireIp(whitelist = listOf(TEST_CLIENT_IP))
        }
  }
}
