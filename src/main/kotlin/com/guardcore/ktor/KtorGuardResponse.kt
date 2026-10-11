package com.guardcore.ktor

import com.guardcore.protocols.GuardResponse
import com.guardcore.protocols.GuardResponseFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes

/**
 * Port of the `StarletteGuardResponse` adapter surface (fastapi-guard guard/adapters.py:118): a
 * GuardResponse the factory and the response-exit path build and mutate.
 *
 * Block and error responses are plain text like the Python family (fastapi-guard #144): the message
 * itself, not JSON-wrapped. A custom response modifier can still override the content type.
 */
public class KtorGuardResponse(
    override val statusCode: Int,
    bodyBytes: ByteArray,
) : GuardResponse {
  /** Python `create_response` shape: string content. */
  public constructor(
      statusCode: Int,
      content: String,
  ) : this(statusCode, content.encodeToByteArray())

  override val headers: MutableMap<String, String> = linkedMapOf()

  private var currentBody: ByteArray = bodyBytes

  init {
    headers["content-type"] = "text/plain; charset=utf-8"
  }

  override val body: ByteArray
    get() = currentBody

  /** The response-exit path materializes a live view over the outgoing content. */
  internal fun overrideBody(bytes: ByteArray) {
    currentBody = bytes
  }
}

/**
 * Port of the `StarletteResponseFactory` adapter surface (fastapi-guard guard/adapters.py:164):
 * `create_response` and `create_redirect_response` over [KtorGuardResponse].
 */
public class KtorGuardResponseFactory : GuardResponseFactory {
  /** Python `create_response` (adapters.py:165). */
  override fun createResponse(content: String, statusCode: Int): GuardResponse =
      KtorGuardResponse(statusCode, content)

  /** Python `create_redirect_response` (adapters.py:170): Location header, empty body. */
  override fun createRedirectResponse(url: String, statusCode: Int): GuardResponse {
    val response = KtorGuardResponse(statusCode, "")
    response.headers["location"] = url
    return response
  }
}

/**
 * Sends a facade [GuardResponse] through the Ktor call exactly as the factory produced it: status
 * code, every header, and the body bytes. Redirect responses (a `location` header) go through the
 * same byte path so the observable response is byte-exact with the factory output.
 */
public suspend fun ApplicationCall.respondGuardResponse(response: GuardResponse) {
  for ((name, value) in response.headers) {
    this.response.header(name, value)
  }
  val contentType = response.headers["content-type"]?.let { ContentType.parse(it) }
  respondBytes(
      bytes = response.body ?: ByteArray(0),
      contentType = contentType,
      status = HttpStatusCode.fromValue(response.statusCode),
  )
}
