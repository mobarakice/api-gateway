plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
    // The config worker runs in every gateway process: its unit tests also run as a native image.
    id("gw.native-conventions")
}

dependencies {
    api(libs.vertx.core)
    api(project(":gateway-core"))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    testImplementation(libs.awaitility)
    testRuntimeOnly(project(":gateway-observability"))
}
