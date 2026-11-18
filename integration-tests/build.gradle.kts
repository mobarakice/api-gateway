// Testcontainers suites (Docker required) against the real gateway process, on the JVM and native.
//   ./gradlew :integration-tests:integrationTest         gateway launched as a JVM process
//   ./gradlew :integration-tests:nativeIntegrationTest   gateway launched as the native binary
// Neither is part of `build`, which stays Docker-free.
plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
    id("gw.test-tls-conventions")
}

val gatewayRuntime = configurations.create("gatewayRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    gatewayRuntime(project(":gateway-app"))
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(project(":gateway-config"))
                implementation(project(":test-support"))
                implementation(platform(libs.vertx.bom))
                implementation(libs.vertx.core)
                implementation(platform(libs.testcontainers.bom))
                implementation(libs.testcontainers.junit)
                implementation(libs.assertj.core)
                implementation(libs.awaitility)
                compileOnly(libs.vertx.codegen.api)
                compileOnly(libs.vertx.codegen.json)
            }
        }
    }
}

val integrationTestSourceSet = sourceSets.named("integrationTest")
val javaLauncher = javaToolchains.launcherFor(java.toolchain)
val nativeBinary = rootProject.layout.projectDirectory.file("gateway-app/build/native/nativeCompile/gateway")

tasks.named<Test>("integrationTest") {
    description = "Runs the integration suites against the gateway as a JVM process."
    val cp: FileCollection = gatewayRuntime
    val java = javaLauncher.map { it.executablePath.asFile.absolutePath }
    inputs.files(cp).withPropertyName("gatewayRuntime")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dgw.it.mode=jvm", "-Dgw.it.java=" + java.get(), "-Dgw.it.classpath=" + cp.asPath)
    })
}

tasks.register<Test>("nativeIntegrationTest") {
    description = "Runs the integration suites against the native gateway binary."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.get().output.classesDirs
    classpath = integrationTestSourceSet.get().runtimeClasspath
    dependsOn(":gateway-app:nativeCompile")
    val binary = nativeBinary.asFile.absolutePath
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dgw.it.mode=native", "-Dgw.it.binary=$binary")
    })
}
