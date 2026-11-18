plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
}

dependencies {
    api(libs.vertx.core)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
}
