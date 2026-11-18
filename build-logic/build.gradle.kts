plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradleplugin.spotbugs)
    implementation(libs.gradleplugin.graalvm.native)
}
