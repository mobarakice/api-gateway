plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
    // Metrics and the tracer run in the request path: their unit tests also run as a native image.
    id("gw.native-conventions")
}

dependencies {
    api(libs.slf4j.api)
    testImplementation(libs.jackson.databind)
}
