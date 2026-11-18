// Generates a throwaway test PKI (apigw.testsupport.TestPki) before tests run, on the JVM and native.
// Tests read the directory from the "gw.test.tls.dir" system property.
plugins {
    java
}

val testPki = configurations.create("testPki") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    testPki(project(":test-support"))
}

val tlsDir = layout.buildDirectory.dir("test-tls")
val tlsPath = tlsDir.map { it.asFile.absolutePath }

val generateTestTls = tasks.register<JavaExec>("generateTestTls") {
    group = "verification"
    description = "Generates a throwaway CA, server and client certificate for tests."
    classpath = testPki
    mainClass = "apigw.testsupport.TestPki"
    val dir = tlsPath
    argumentProviders.add(CommandLineArgumentProvider { listOf(dir.get()) })
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    outputs.dir(tlsDir)
    // Keys are random and short-lived: always regenerate.
    outputs.upToDateWhen { false }
}

tasks.withType<Test>().configureEach {
    dependsOn(generateTestTls)
    val dir = tlsPath
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dgw.test.tls.dir=" + dir.get()) })
}

pluginManager.withPlugin("org.graalvm.buildtools.native") {
    tasks.matching { it.name == "nativeTest" }.configureEach { dependsOn(generateTestTls) }
    extensions.configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension> {
        binaries.named("test") {
            val dir = tlsPath
            runtimeArgs.add(dir.map { "-Dgw.test.tls.dir=$it" })
        }
    }
}
