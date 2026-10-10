package com.guardcore.ktor

/** Name the Guard plugin reports to Ktor once the real pipeline lands. */
const val GUARD_PLUGIN_NAME: String = "GuardKtorPlugin"

/**
 * W0 scaffold placeholder shaped like a Ktor plugin entry point.
 *
 * The real createApplicationPlugin installation (request validation, rate limiting, detection
 * pipeline) lands in a later milestone; this stub exists so the artifact, toolchain, and CI wiring
 * are exercisable end to end.
 */
fun guardKtorPlugin(): String = GUARD_PLUGIN_NAME
