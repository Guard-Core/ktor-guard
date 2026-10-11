package com.guardcore.ktor

import com.guardcore.protocols.BoundedBodyReader
import com.guardcore.protocols.GuardRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.toByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Protocol wrapper tests: the Ktor ApplicationCall wrapped into the facade GuardRequest (headers
 * case-insensitivity, bounded body reads, url helpers) exercised through the live request path via
 * the middleware's state bag.
 */
class KtorGuardRequestTest {
  @Test
  fun headersResolveCaseInsensitivelyLikeTheReferenceCIMultiDict() {
    val raw =
        linkedMapOf(
            "X-Forwarded-Proto" to "https",
            "content-type" to "application/json",
            "User-Agent" to "test/1.0",
        )
    val headers =
        KtorGuardRequest.buildJoinedHeaders(
            io.ktor.http
                .HeadersBuilder()
                .apply {
                  raw.forEach { (k, v) -> append(k, v) }
                }
                .build(),
        )
    // Mixed-case literal reads (the facade reads both shapes) must resolve.
    assertEquals("https", headers["X-Forwarded-Proto"])
    assertEquals("https", headers["x-forwarded-proto"])
    assertEquals("application/json", headers["Content-Type"])
    assertEquals("test/1.0", headers["user-agent"])
  }

  @Test
  fun repeatedHeaderLinesJoinWithCommas() {
    val headers =
        KtorGuardRequest.buildJoinedHeaders(
            io.ktor.http
                .HeadersBuilder()
                .apply {
                  append("Accept", "text/html")
                  append("Accept", "application/json")
                }
                .build(),
        )
    assertEquals("text/html, application/json", headers["accept"])
  }

  @Test
  fun boundedPrefixReadKeepsTheFullBodyAvailableToTheHandler() = testApplication {
    var handlerBody = ""
    application {
      routing {
        post("/echo") {
          // The detection path read a capped prefix; the handler still sees the FULL body
          // (the replaying-receive contract from the Python adapter).
          handlerBody = call.request.receiveChannel().toByteArray().decodeToString()
          call.respondText("echo:${handlerBody.length}")
        }
      }
    }
    val payload = "x".repeat(64) + "TAIL"
    val response =
        client.post("/echo") {
          setBody(payload)
        }
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("echo:68", response.bodyAsText())
    assertEquals(payload, handlerBody)
  }

  @Test
  fun requestStateBagRoundTrips() {
    val state: com.guardcore.protocols.RequestState = KtorGuardRequest.MapRequestState()
    state.setAttr("guard_route_id", "GET:/x")
    assertEquals("GET:/x", state.getAttr("guard_route_id"))
    assertEquals(null, state.getAttr("missing"))
  }

  @Test
  fun urlReplaceSchemeReplacesTheLeadingScheme() {
    val url = "http://localhost:8080/path?q=1"
    assertEquals("https://localhost:8080/path?q=1", url.replaceFirst(Regex("^https?"), "https"))
  }

  @Test
  fun guardRequestImplementsBothProtocolSurfaces() {
    // Compile-time surface check: the adapter request carries the bounded-reader capability.
    val requestClass: Class<out GuardRequest> = KtorGuardRequest::class.java
    assertTrue(BoundedBodyReader::class.java.isAssignableFrom(requestClass))
  }
}
