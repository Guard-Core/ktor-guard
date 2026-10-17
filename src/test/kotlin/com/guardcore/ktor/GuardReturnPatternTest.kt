package com.guardcore.ktor

import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlin.test.Test
import kotlin.test.assertEquals

/** Body based return_pattern rules read the response body through BoundedResponseBodyReader. */
class GuardReturnPatternTest {
  @Test
  fun bodyReturnPatternBansTheClientOnceTheThresholdIsExceeded() {
    val config = baseConfig { behaviorScanResponseBody = true }
    val decorator = GuardRouteDecorator(config)
    guardTest(
        config,
        decorator = decorator,
        routes = {
          get("/leaky") { call.respondText("here is a leaked-token") }
              .guardReturnMonitor(decorator, pattern = "leaked-token", maxOccurrences = 1)
        },
    ) {
      repeat(2) {
        val response =
            client.get("/leaky") { headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) } }
        assertEquals(HttpStatusCode.OK, response.status)
      }
      // The tracker trips when the count exceeds the threshold, so the second hit bans.
      val banned =
          client.get("/leaky") { headers { append(HttpHeaders.XForwardedFor, TEST_CLIENT_IP) } }
      assertEquals(HttpStatusCode.Forbidden, banned.status)
    }
  }
}
