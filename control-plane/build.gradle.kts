plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
    id("gw.native-conventions")
    id("gw.test-tls-conventions")
    application
}

dependencies {
    // Validation reuses the gateway's own decoder, validator and snapshot compiler (ADR 0020).
    implementation(project(":config-model"))
    implementation(project(":gateway-config"))
    implementation(project(":gateway-core"))
    implementation(project(":gateway-plugins"))
    implementation(project(":gateway-observability"))
    implementation(libs.vertx.core)
    implementation(libs.slf4j.api)
    implementation(libs.jackson.databind)
    testImplementation(testFixtures(project(":gateway-plugins")))
    testImplementation(libs.awaitility)
}

application {
    mainClass = "apigw.controlplane.ControlPlaneMain"
    applicationName = "control-plane"
    applicationDefaultJvmArgs = listOf("-XX:+ExitOnOutOfMemoryError")
}

graalvmNative {
    binaries.named("main") {
        imageName = "control-plane"
        sharedLibrary = false
        mainClass = "apigw.controlplane.ControlPlaneMain"
    }
}
