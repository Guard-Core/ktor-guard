pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

// ---------------------------------------------------------------------------
// Engine dependency (com.guard-core:guard-core:4.3.3) via composite build.
//
// GUARD_CORE_USE_COMPOSITE (environment variable):
//   unset / "1" / "true"  -> composite ON (default, dev + CI-with-pin).
//   "0" / "false"         -> composite OFF: no includeBuild; the declared
//     coordinates resolve from the repositories (mavenLocal, then Maven
//     Central). Release builds run this way so the published artifact graph
//     is proven against the real engine artifact (docs/release.md).
//
// ktor-guard contains no security logic: the engine lives in
// Guard-Core/guard-core-jvm and is built from source here through includeBuild
// + dependency substitution (the Gradle analog of the Rust path+version
// pattern). Declared coordinates stay com.guard-core:guard-core:4.3.3 and
// com.guard-core:guard-core-engine:4.3.3 (groupId com.guard-core is the
// Central Portal namespace; Kotlin packages stay com.guardcore.*; lockstep
// with the guard-core 4.3.3 release); substitution redirects them to the
// included build until the artifacts publish to Maven Central.
//
// Layout: the engine checkout sits as a sibling of this repo. CI checks it out
// with actions/checkout (repository: Guard-Core/guard-core-jvm, ref: 5f4ea56,
// path: guard-core-jvm); locally clone it with
//   git clone https://github.com/Guard-Core/guard-core-jvm ../guard-core-jvm
// ---------------------------------------------------------------------------
val compositeRaw: String? = System.getenv("GUARD_CORE_USE_COMPOSITE")
val useComposite: Boolean =
    compositeRaw == null ||
        (compositeRaw != "0" && !compositeRaw.equals("false", ignoreCase = true))

dependencyResolutionManagement {
  repositories {
    // Composite OFF (release builds) resolves the engine from mavenLocal
    // first, then Maven Central: mavenLocal is empty on release runners.
    if (!useComposite) {
      mavenLocal()
    }
    mavenCentral()
  }
}

rootProject.name = "ktor-guard"

if (useComposite) {
  val engineDir: String =
      listOf("guard-core-jvm", "../guard-core-jvm").firstOrNull {
        file(it).resolve("settings.gradle.kts").exists()
      }
          ?: throw GradleException(
              "guard-core-jvm engine checkout not found. CI provides it via the " +
                  "actions/checkout step (path: guard-core-jvm); locally run: " +
                  "git clone https://github.com/Guard-Core/guard-core-jvm ../guard-core-jvm",
          )

  includeBuild(engineDir) {
    dependencySubstitution {
      substitute(module("com.guard-core:guard-core")).using(project(":guard-core"))
      substitute(module("com.guard-core:guard-core-engine")).using(project(":guard-core-engine"))
    }
  }
} else {
  logger.lifecycle(
      "GUARD_CORE_USE_COMPOSITE is disabled: resolving com.guard-core:guard-core from the " +
          "repositories (mavenLocal, then Maven Central).",
  )
}
