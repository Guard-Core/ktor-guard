pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

rootProject.name = "ktor-guard"

// Engine dependency (com.guardcore:guard-core:4.3.2) via composite build.
//
// ktor-guard contains no security logic: the engine lives in
// Guard-Core/guard-core-jvm and is built from source here through includeBuild
// + dependency substitution (the Gradle analog of the Rust path+version
// pattern). Declared coordinates stay com.guardcore:guard-core:4.3.2 and
// com.guardcore:guard-core-engine:4.3.2 (lockstep with the guard-core 4.3.2
// release); substitution redirects them to the included build until the
// artifacts publish to Maven Central.
//
// Layout: the engine checkout sits as a sibling of this repo. CI checks it out
// with actions/checkout (repository: Guard-Core/guard-core-jvm, ref: 5f4ea56,
// path: guard-core-jvm); locally clone it with
//   git clone https://github.com/Guard-Core/guard-core-jvm ../guard-core-jvm
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
    substitute(module("com.guardcore:guard-core")).using(project(":guard-core"))
    substitute(module("com.guardcore:guard-core-engine")).using(project(":guard-core-engine"))
  }
}
