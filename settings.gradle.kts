pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "api-gateway"

include(
    "config-model",
    "gateway-core",
    "gateway-plugins",
    "gateway-proxy",
    "gateway-config",
    "gateway-observability",
    "gateway-app",
    "control-plane",
    "test-support",
    "integration-tests",
    "benchmarks",
)
