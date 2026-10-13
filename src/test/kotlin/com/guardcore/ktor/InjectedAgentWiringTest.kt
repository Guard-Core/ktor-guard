package com.guardcore.ktor

import com.guardcore.config.SecurityConfig
import com.guardcore.config.SecurityConfigBuilder
import com.guardcore.core.events.AgentTelemetryMember
import com.guardcore.protocols.AgentHandlerProtocol
import com.guardcore.protocols.RedisHandlerProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class InjectedAgentWiringTest {
  /** The guard-agent-jvm GuardAgentTelemetry shape: a facade protocol and a composite member. */
  private class RecordingTelemetry : AgentHandlerProtocol, AgentTelemetryMember {
    var starts = 0
    var stops = 0
    val events = mutableListOf<Map<String, Any?>>()
    val metrics = mutableListOf<Map<String, Any?>>()

    override suspend fun initializeRedis(redisHandler: RedisHandlerProtocol) = Unit

    override suspend fun initializeRedis(redisHandler: Any?) = Unit

    override suspend fun sendEvent(event: Map<String, Any?>) {
      events.add(event)
    }

    override suspend fun sendMetric(metric: Map<String, Any?>) {
      metrics.add(metric)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun sendEvent(event: Any?) {
      (event as? Map<String, Any?>)?.let { events.add(it) }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun sendMetric(metric: Any?) {
      (metric as? Map<String, Any?>)?.let { metrics.add(it) }
    }

    override suspend fun start() {
      starts++
    }

    override suspend fun stop() {
      stops++
    }

    override suspend fun flushBuffer() = Unit

    override suspend fun getDynamicRules(): Any? = null

    override suspend fun healthCheck(): Boolean = true
  }

  private fun agentConfig(): SecurityConfig =
      SecurityConfigBuilder()
          .apply {
            enableRedis = false
            enableAgent = true
            agentApiKey = "test-key"
          }
          .build()

  @Test
  fun enableAgentWithoutAHandlerDegrades() {
    val middleware = GuardCoreMiddleware(agentConfig(), null, null)
    assertTrue(middleware.agentDegraded)
  }

  @Test
  fun injectedHandlerIsStartedReceivesEventsAndMetricsAndStops() = runBlocking {
    val telemetry = RecordingTelemetry()
    val middleware = GuardCoreMiddleware(agentConfig(), null, telemetry)
    assertFalse(middleware.agentDegraded)

    middleware.initialize()
    middleware.agentHandler!!.sendEvent(linkedMapOf<String, Any?>("event_type" to "ip_banned"))
    middleware.metricsCollector.sendMetric("response_time", 1.0)
    assertEquals(1, telemetry.starts)
    assertEquals(listOf<Any?>("ip_banned"), telemetry.events.map { it["event_type"] })
    assertEquals(listOf<Any?>("response_time"), telemetry.metrics.map { it["metric_type"] })

    middleware.shutdown()
    assertEquals(1, telemetry.stops)
  }
}
