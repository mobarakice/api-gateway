plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
    id("gw.native-conventions")
}

dependencies {
    api(project(":gateway-core"))
}
