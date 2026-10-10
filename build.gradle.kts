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
  implementation(libs.ktor.server.core)

  // The engine dependency (com.guardcore:guard-core:4.3.2) lands in W6 when
  // the engine artifact is on Maven Central.
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
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
}

// The JaCoCo coverage gate lands with real code in a later milestone. The
// plugin is applied now so reports exist from the first real classes on.
