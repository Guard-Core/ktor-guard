plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.spotless)
  alias(libs.plugins.detekt)
  jacoco
}

group = "com.guardcore"

version = "1.0.0-SNAPSHOT"

kotlin {
  jvmToolchain(17)
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(libs.kotlin.stdlib)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.ktor.server.core)

  // The engine (facade): resolved from the guard-core-jvm included build via
  // dependency substitution (see settings.gradle.kts). ktor-guard wires the
  // facade only; no security logic lives here.
  api(libs.guard.core)

  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.kotlin.test)
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showStandardStreams = true
  }
}

spotless {
  kotlin {
    ktfmt()
  }
  kotlinGradle {
    ktfmt()
  }
}

detekt {
  buildUponDefaultConfig = true
  config.setFrom(project.file("detekt.yml"))
}

// JaCoCo reports exist from the first real classes on; the coverage gate
// lands with the family quality-gate milestone.
