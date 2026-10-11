package com.guardcore.ktor

/**
 * Family convention re-exports (every adapter re-exports the engine config surface from the adapter
 * package): applications import `com.guardcore.ktor.SecurityConfig` and friends without touching
 * the facade package.
 */
public typealias SecurityConfig = com.guardcore.config.SecurityConfig

/** Builder re-export (same surface as the facade builder). */
public typealias SecurityConfigBuilder = com.guardcore.config.SecurityConfigBuilder

/** Per-route behavior rule type re-export (the decorator surface uses it). */
public typealias BehaviorRule = com.guardcore.handlers.BehaviorRule

/** Route auth verifier re-export (require_auth / api_key_auth verifiers). */
public typealias RouteAuthVerifier = com.guardcore.decorators.RouteAuthVerifier
