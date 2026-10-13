# Adapter parity: fastapi-guard 8.1.0 (PureASGISecurityMiddleware)

Assessment of the W10 parity wave (2026-10-13): what fastapi-guard 8.1.0
changed, how this adapter's native idiom maps onto it, and which of the
pure-streaming properties hold here. Conclusion up front: all three
pure-streaming properties already hold natively; zero code changes.

## What upstream shipped

fastapi-guard 8.1.0 (2026-10-10, #156) added `PureASGISecurityMiddleware`: the
full screening pipeline running on raw ASGI without `BaseHTTPMiddleware`. It
exists to win three properties the classic middleware loses to Starlette's
anyio task group:

1. No downstream wrapping that could cancel in-flight work (a client
   disconnect can never cancel an in-flight database call).
2. Unbuffered response streaming (security and CORS headers injected directly
   on `http.response.start`, body bytes never held).
3. A caching receive that replays the request body to the application after
   the guard reads it.

Scope note from the upstream release: decorator-driven response rules (return
patterns) and `custom_response_modifier` need a materialized `Response`
object; they stay on the classic middleware and both classes ship side by
side.

## The idiom mapping

This adapter's native idiom is the Ktor server plugin: the request side is a
custom `Hook` intercepting the `Plugins` phase (`GuardRequestHook`), the
response side is the `ResponseBodyReadyForSend` hook, and a block
short-circuits with `finish()` so the route handler never starts. Ktor never
had a `BaseHTTPMiddleware` analog: the plugin's hooks run on the framework's
own call pipeline, which is the shape PureASGI was built to reach. The
upstream release validates an idiom this adapter has used since W6.

## Property-by-property

### 1. No downstream cancelling wrap: HOLDS

The plugin adds no wrapping of its own: `GuardRequestHook` runs its checks
inside the call pipeline intercept, then returns and lets the handler execute
in the framework's own coroutine (the dispatch comment in GuardCorePlugin.kt:
"the call_next leg ... Ktor's suspend dispatch performs after this hook
returns"). There is no middleware-owned coroutine scope around the downstream
code, so there is no middleware-induced cancellation surface; the only
cancellation source is the engine's own native semantics. On a block, the
hook finishes the pipeline before the handler starts, mirroring the reference
dispatch ordering.

### 2. Unbuffered response streaming: HOLDS

The response hook materializes body bytes only for
`OutgoingContent.ByteArrayContent` (`materializeBody`); streamed bodies
(`WriteChannelContent` and friends) are never read. Streamed responses get
additions only - header appends (engine-managed headers skipped) and a status
change through the response status channel (`reflectStreamed`, documented
in-code since W6: "Streamed bodies: header additions and a status change only
(no replayable body)"). Buffered bodies (`respondText`/`respondBytes`) rebuild
the content with the full processed header set, so behavioral return rules
and the response modifier carry exactly on them - the same scoping decision
as the upstream scope note (the features that need a materialized response
get one; the features that do not, never hold the bytes).

### 3. A caching receive that replays the body: HOLDS

`KtorGuardRequest` drains the request body exactly once (bounded, under the
facade's read timeout), caches the bytes, and installs the drained body back
as the call's receive channel, so the route handler observes the same full
body the scan saw while repeat reads never re-consume the stream (the
documented bounded-read replay contract, the Ktor idiom for the reference's
replaying receive).

## Verdict

Zero code changes in the 8.1.0 parity wave. The Ktor hook idiom already
satisfies the pure-streaming properties PureASGISecurityMiddleware was built
to win: no downstream wrap, streamed bodies untouched (additions only), and a
replaying cached receive.
