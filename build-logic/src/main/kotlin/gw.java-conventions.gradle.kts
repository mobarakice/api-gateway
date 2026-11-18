// Shared Java rules for every Gradle module.
plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
        // Oracle GraalVM: one JDK for JVM runs and native-image, so JVM vs native compare like for like.
        vendor = JvmVendorSpec.ORACLE
        nativeImageCapable = true
    }
}


// Vert.x 5.2 depends on Netty's HTTP/3, native QUIC and tcnative (OpenSSL) jars. The gateway uses
// JDK TLS and no HTTP/3 (ADR 0003), so they are excluded: smaller image, smaller attack surface,
// and no build-time initialisation of tcnative classes in Native Image.
// netty-codec-classes-quic stays: Vert.x's ConnectionBase.remoteAddress() does `instanceof QuicChannel`,
// so without it every remoteAddress() call throws NoClassDefFoundError (found in M2).
configurations.configureEach {
    exclude(group = "io.netty", module = "netty-codec-http3")
    exclude(group = "io.netty", module = "netty-codec-native-quic")
    exclude(group = "io.netty", module = "netty-tcnative-classes")
}

dependencies {
    "implementation"(platform(libs.findLibrary("vertx-bom").get()))
    "implementation"(platform(libs.findLibrary("netty-bom").get()))
    "implementation"(platform(libs.findLibrary("jackson-bom").get()))
    "compileOnly"(libs.findLibrary("spotbugs-annotations").get())
    "compileOnly"(libs.findLibrary("vertx-codegen-api").get())
    "compileOnly"(libs.findLibrary("vertx-codegen-json").get())
    "testCompileOnly"(libs.findLibrary("vertx-codegen-api").get())
    "testCompileOnly"(libs.findLibrary("vertx-codegen-json").get())
    "testCompileOnly"(libs.findLibrary("spotbugs-annotations").get())
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(platform(libs.findLibrary("testcontainers-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj-core").get())
    "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = libs.findVersion("java").get().requiredVersion.toInt()
    // Zero-warning build. "-processing" is excluded: annotation-processor chatter is not a code defect.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 1
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
