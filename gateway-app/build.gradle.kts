plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
    id("gw.native-conventions")
    id("gw.test-tls-conventions")
    application
}

dependencies {
    implementation(project(":gateway-config"))
    implementation(libs.vertx.core)
    implementation(libs.slf4j.api)
    implementation(project(":gateway-observability"))
    testImplementation(libs.awaitility)
}

application {
    mainClass = "apigw.app.GatewayMain"
    applicationName = "gateway"
    // jvm-prod distribution (installDist / distZip): same code and config as the native binary.
    applicationDefaultJvmArgs = listOf("-XX:+ExitOnOutOfMemoryError", "-XX:+UseG1GC")
}

graalvmNative {
    binaries.named("main") {
        imageName = "gateway"
        sharedLibrary = false
        mainClass = "apigw.app.GatewayMain"
        buildArgs.addAll(providers.gradleProperty("gw.nativeExtraArgs").map { it.split(" ") }.orElse(emptyList()))
    }
}
