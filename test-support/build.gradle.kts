// Test-only helpers. Never a dependency of shipped code.
plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
}

dependencies {
    api(libs.bouncycastle.pkix)
    api(platform(libs.testcontainers.bom))
    api(libs.testcontainers.core)
}

val etcdImage = libs.versions.etcdImage.get()
val redisImage = libs.versions.redisImage.get()
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("etcdImage" to etcdImage, "redisImage" to redisImage)
    inputs.properties(props)
    filesMatching("test-support.properties") { expand(props) }
}
