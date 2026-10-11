plugins {
  kotlin("jvm") version "2.4.21"
  application
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
  implementation("com.guardcore:ktor-guard:1.0.0-SNAPSHOT")
  implementation("io.ktor:ktor-server-core:3.6.0")
  implementation("io.ktor:ktor-server-cio:3.6.0")
}

application {
  mainClass.set("com.guardcore.ktor.demo.MainKt")
}
