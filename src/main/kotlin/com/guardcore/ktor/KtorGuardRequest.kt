package com.guardcore.ktor

import com.guardcore.protocols.BoundedBodyReader
import com.guardcore.protocols.GuardRequest
import com.guardcore.protocols.RequestState
import com.guardcore.util.BodyReader
import io.ktor.http.Headers
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray

/**
 * Port of the `StarletteGuardRequest` adapter surface (fastapi-guard guard/adapters.py): the Ktor
 * [ApplicationCall] wrapped into the facade's [GuardRequest] protocol plus the [BoundedBodyReader]
 * capability.
 *
 * Body semantics (the bounded-read replay contract): the first body access drains the request body
 * once (under the facade's default read timeout) and caches it; [readBodyPrefix] hands out capped
 * views and [body] the full bytes, so repeat reads never re-consume the stream. The drained body is
 * then installed back as the call's receive channel, so the route handler observes the same full
 * body the adapter saw - the Ktor idiom for the Python adapter's replaying receive (the scan stays
 * capped through the facade's BodyReader; the replay buffer holds the body like Starlette's own
 * `_body` cache does).
 */
public class KtorGuardRequest(
    private val call: ApplicationCall,
    private val connectingIp: String?,
) : GuardRequest, BoundedBodyReader {
  private val bodyMutex = Mutex()
  private var bodyCache: ByteArray? = null
  private var channelDelegate: ByteReadChannel? = null

  private val requestState: RequestState = MapRequestState()

  /** Python property `url_path` (adapters.py:43). */
  override val urlPath: String
    get() = call.request.path()

  /** Python property `url_scheme` (adapters.py:46). */
  override val urlScheme: String
    get() = call.request.origin.scheme

  /** Python property `url_full` (adapters.py:49): scheme://host/path?query. */
  override val urlFull: String
    get() {
      val origin = call.request.origin
      val host = origin.serverHost
      val port = origin.serverPort
      val hostPart = if (port == defaultPort(origin.scheme)) host else "$host:$port"
      return "${origin.scheme}://$hostPart${call.request.uri}"
    }

  /** Python method `url_replace_scheme` (adapters.py:52). */
  override fun urlReplaceScheme(scheme: String): String =
      urlFull.replaceFirst(Regex("^https?"), scheme)

  /** Python property `method` (adapters.py:56). */
  override val method: String
    get() = call.request.httpMethod.value

  /** Python property `client_host` (adapters.py:59): the connecting peer address. */
  override val clientHost: String?
    get() = connectingIp

  /** Python property `headers` (adapters.py:64): joined, case-insensitive lookup. */
  override val headers: Map<String, String> by lazy {
    buildJoinedHeaders(call.request.headers)
  }

  /** Python property `query_params` (adapters.py:69). */
  override val queryParams: Map<String, String> by lazy {
    buildQueryParams(call.request.queryParameters)
  }

  /** Python async `body` (adapters.py:72): cached full read. */
  override suspend fun body(): ByteArray {
    ensureBody()
    return bodyCache!!
  }

  /** Python async `read_body_prefix` (adapters.py:82): size-capped prefix of the cached body. */
  override suspend fun readBodyPrefix(maxBytes: Int): ByteArray {
    if (maxBytes <= 0) {
      return ByteArray(0)
    }
    ensureBody()
    val cached = bodyCache!!
    return cached.copyOfRange(0, minOf(maxBytes, cached.size))
  }

  /** Python property `state` (adapters.py:133): the per-request scratch bag. */
  override val state: RequestState
    get() = requestState

  /** Python property `scope` (adapters.py:136): the raw framework call. */
  override val scope: Map<String, Any?>
    get() = mapOf("call" to call)

  /**
   * Drains the request body once (timeout-bounded like `BodyReader.safeRead`), caches it, and
   * installs the cached bytes back as the call's receive channel for downstream readers.
   */
  private suspend fun ensureBody() {
    bodyCache?.let {
      return
    }
    bodyMutex.withLock {
      if (bodyCache != null) {
        return
      }
      val channel = requestChannel()
      val drained =
          try {
            withTimeout((BodyReader.DEFAULT_BODY_READ_TIMEOUT * 1000).toLong()) {
              channel.readRemaining().readByteArray()
            }
          } catch (_: Exception) {
            // Python `_safe_read` failure path: null -> empty body.
            ByteArray(0)
          }
      bodyCache = drained
      installReplayChannel(drained)
    }
  }

  /** Installs the drained body as the call's receive channel (the replay step). */
  @OptIn(InternalAPI::class)
  private fun installReplayChannel(body: ByteArray) {
    val pipelineRequest = call.request as? io.ktor.server.request.PipelineRequest ?: return
    try {
      pipelineRequest.setReceiveChannel(ByteReadChannel(body))
    } catch (_: Exception) {
      // A reset failure leaves the drained channel in place; handlers that read through
      // the guard-cached surface still see the body.
    }
  }

  private suspend fun requestChannel(): ByteReadChannel {
    channelDelegate?.let {
      return it
    }
    val channel = call.receiveChannel()
    channelDelegate = channel
    return channel
  }

  private fun defaultPort(scheme: String): Int =
      when (scheme.lowercase()) {
        "https" -> 443
        else -> 80
      }

  /** True when the body has been drained (the plugin's response phase checks this). */
  internal fun isBodyMaterialized(): Boolean = bodyCache != null

  /** Per-request attribute bag standing in for Python `request.state`. */
  internal class MapRequestState : RequestState {
    private val attrs: MutableMap<String, Any?> = linkedMapOf()

    override fun getAttr(name: String): Any? = attrs[name]

    override fun setAttr(name: String, value: Any?) {
      attrs[name] = value
    }
  }

  internal companion object {
    /**
     * Port of `_join_repeated_header_lines` (adapters.py:23): repeated header lines join with ", ".
     * Lookup is case-insensitive like the Python `Headers` CIMultiDict the checks read through,
     * preserving first-seen casing for iteration.
     */
    internal fun buildJoinedHeaders(headers: Headers): Map<String, String> {
      val joined: MutableMap<String, String> = linkedMapOf()
      for (name in headers.names()) {
        val values = headers.getAll(name) ?: continue
        joined[name] = values.joinToString(", ")
      }
      return CaseInsensitiveHeaders(joined)
    }

    /** Python `request.query_params` (Starlette multi-dict read single-valued). */
    internal fun buildQueryParams(parameters: Parameters): Map<String, String> {
      val joined: MutableMap<String, String> = linkedMapOf()
      for (name in parameters.names()) {
        joined[name] = parameters.getAll(name)?.joinToString(", ") ?: continue
      }
      return CaseInsensitiveHeaders(joined)
    }
  }
}

/**
 * Map with case-insensitive [get]/[containsKey] over first-seen-cased entries: the JVM analog of
 * the Starlette CIMultiDict the facade's header reads go through (the facade reads request headers
 * with mixed-case literal keys; both `X-Forwarded-Proto` and lowercase reads must resolve).
 */
internal class CaseInsensitiveHeaders(
    private val delegate: Map<String, String>,
) : Map<String, String> by delegate {
  private val lowerIndex: Map<String, String> =
      delegate.entries.associate { (k, v) -> k.lowercase() to v }

  override fun containsKey(key: String): Boolean = lowerIndex.containsKey(key.lowercase())

  override fun get(key: String): String? = lowerIndex[key.lowercase()]
}
