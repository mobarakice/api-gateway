plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
}

dependencies {
    api(libs.vertx.core)
    api(project(":gateway-core"))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    testImplementation(libs.awaitility)
}
