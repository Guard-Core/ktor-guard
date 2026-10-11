# guard-demo-ktor

A small guarded Ktor application demonstrating the `com.guardcore:ktor-guard` adapter: the
GuardCore server plugin runs the guard-core engine pipeline on every request, and per-route
configuration comes from the `GuardRouteDecorator` route extensions.

## Routes

| Route       | Config                                     | Behavior                                             |
|-------------|--------------------------------------------|------------------------------------------------------|
| `GET /`     | app-wide `blocked_user_agents = ["badbot"]`| Passes the pipeline; badbot user agents get 403.      |
| `GET /limited` | `guardRateLimit(requests = 2, window = 60)` | 200 twice, then 429 `Too many requests` (Redis-backed). |
| `GET /admin` | `guard { requireIp(whitelist = ["10.0.0.1"]) }` | 403 `Forbidden` unless the caller IP is whitelisted. |

## Run

The adapter and the engine are built from source through composite builds, so the engine checkout
must sit next to this repository:

```bash
git clone https://github.com/Guard-Core/guard-core-jvm ../guard-core-jvm
./gradlew --no-daemon run -p examples/demo-ktor
```

Then try:

```bash
curl localhost:8080/                     # public
curl -A badbot/1.0 localhost:8080/       # 403: User-Agent not allowed
curl localhost:8080/limited              # 200, 200, then 429
curl localhost:8080/admin                # 403: Forbidden
```

Redis is used for the rate-limit window (`enable_redis = true`,
`redis://localhost:6379` by default); start one with
`docker run -d -p 6379:6379 redis:7-alpine`.
