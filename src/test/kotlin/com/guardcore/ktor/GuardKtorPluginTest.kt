package com.guardcore.ktor

import kotlin.test.Test
import kotlin.test.assertEquals

class GuardKtorPluginTest {

  @Test
  fun pluginStubReportsItsName() {
    assertEquals(GUARD_PLUGIN_NAME, guardKtorPlugin())
  }
}
