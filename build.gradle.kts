import org.gradle.jvm.tasks.Jar

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.spotless)
  alias(libs.plugins.detekt)
  jacoco
  `maven-publish`
  signing
}

// Maven coordinates: groupId com.guard-core (the Central Portal namespace;
// it reverses to guard-core.com). Kotlin packages stay com.guardcore.*:
// hyphens are illegal in JVM packages and the groupId/package mismatch is
// standard practice for hyphenated domains.
group = "com.guard-core"

version = "1.0.0-SNAPSHOT"

// Section 14.3 lockfile analog: every configuration resolves against the
// committed gradle.lockfile. A dependency change without
// `./gradlew --write-locks` fails the build, and the lockfile is what the
// osv-scanner dependency-audit job reads.
dependencyLocking {
  lockAllConfigurations()
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(libs.kotlin.stdlib)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.ktor.server.core)

  // The engine (facade): resolved from the guard-core-jvm included build via
  // dependency substitution (see settings.gradle.kts), or from the
  // repositories in composite-OFF (release) mode. ktor-guard wires the
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

// --- Publishing (Maven Central via the Central Portal; see docs/release.md) --
//
// The release workflow publishes the staging bundle to the Portal Publisher
// API with GUARD_CORE_USE_COMPOSITE=false so the engine dependency resolves
// from the repositories (mavenLocal, then Maven Central), never the composite
// checkout. Locally the rehearsal uses publishToMavenLocal; signing activates
// only when the GPG secrets are present so the local rehearsal needs no key
// material.

val sourcesJar by
    tasks.registering(Jar::class) {
      archiveClassifier = "sources"
      from(sourceSets["main"].allSource)
    }

// The standard javadoc task renders nothing for Kotlin-only sources; the jar
// carries the project README so it is never an empty javadoc jar (the Portal
// validator rejects those). Dokka HTML replaces this when the docs wave lands.
val javadocJar by
    tasks.registering(Jar::class) {
      archiveClassifier = "javadoc"
      from(tasks.named("javadoc"))
      from(rootProject.file("README.md"))
    }

publishing {
  publications {
    create<MavenPublication>("maven") {
      groupId = project.group.toString()
      artifactId = project.name
      version = project.version.toString()

      from(components["java"])
      artifact(sourcesJar)
      artifact(javadocJar)

      pom {
        name = "ktor-guard"
        description =
            "Ktor adapter for the Guard ecosystem: a native plugin running the guard-core-jvm " +
                "security pipeline (suspend-native, short-circuit at the Plugins phase)."
        url = "https://github.com/Guard-Core/ktor-guard"

        licenses {
          license {
            name = "MIT License"
            url = "https://opensource.org/licenses/MIT"
          }
        }

        developers {
          developer {
            id = "rennf93"
            name = "Renzo Franceschini"
            email = "rennf93@guard-core.com"
            organization = "Guard-Core"
            organizationUrl = "https://github.com/Guard-Core"
          }
        }

        scm {
          connection = "scm:git:https://github.com/Guard-Core/ktor-guard.git"
          developerConnection = "scm:git:ssh://github.com/Guard-Core/ktor-guard.git"
          url = "https://github.com/Guard-Core/ktor-guard"
        }
      }
    }
  }

  repositories {
    maven {
      name = "CentralStaging"
      url = uri(layout.buildDirectory.dir("central-staging"))
    }
  }
}

signing {
  val signingKey = System.getenv("GPG_PRIVATE_KEY")
  val signingPassphrase = System.getenv("GPG_PASSPHRASE")
  if (signingKey != null && signingPassphrase != null) {
    useInMemoryPgpKeys(signingKey, signingPassphrase)
    sign(publishing.publications)
  }
}
