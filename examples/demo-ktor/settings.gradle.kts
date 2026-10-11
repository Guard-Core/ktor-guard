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

rootProject.name = "guard-demo-ktor"

// Composite builds: the adapter (this repository's root project) and the engine. The engine
// checkout sits as a sibling of the ktor-guard repository (same layout as CI).
val engineDir: String = listOf("../../../guard-core-jvm", "../../../../guard-core-jvm").firstOrNull {
    file(it).resolve("settings.gradle.kts").exists()
} ?: throw GradleException(
    "guard-core-jvm engine checkout not found; clone it next to the ktor-guard repository: " +
        "git clone https://github.com/Guard-Core/guard-core-jvm ../../../guard-core-jvm",
)

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.guardcore:ktor-guard")).using(project(":"))
    }
}

includeBuild(engineDir) {
    dependencySubstitution {
        substitute(module("com.guardcore:guard-core")).using(project(":guard-core"))
        substitute(module("com.guardcore:guard-core-engine")).using(project(":guard-core-engine"))
    }
}
