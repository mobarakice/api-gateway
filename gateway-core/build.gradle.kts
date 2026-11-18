plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
    id("gw.native-conventions")
}

dependencies {
    api(project(":config-model"))
    api(libs.vertx.core)
    implementation(libs.slf4j.api)
}
