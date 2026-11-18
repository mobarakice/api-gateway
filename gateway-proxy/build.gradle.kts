plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
    id("gw.native-conventions")
}

dependencies {
    api(project(":gateway-core"))
    implementation(libs.slf4j.api)
    testImplementation(libs.awaitility)
    testRuntimeOnly(project(":gateway-observability"))
}
