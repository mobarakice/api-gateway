// Native Image rules for modules that produce or test native code.
plugins {
    java
    id("org.graalvm.buildtools.native")
}

graalvmNative {
    // Metadata comes from the libraries themselves (Vert.x, Netty) and from the reviewed files under
    // src/main/resources/META-INF/native-image/. The GraalVM reachability metadata repository is
    // off: no current dependency needs it, and it would add unreviewed metadata. Enable it with
    // -Pgw.metadataRepository=true (requires GraalVM 25.0.1+ for its schema) when one does.
    metadataRepository {
        enabled = providers.gradleProperty("gw.metadataRepository").map { it.toBoolean() }.orElse(false)
    }
    toolchainDetection = false
    binaries.configureEach {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.ORACLE
            nativeImageCapable = true
        }
        buildArgs.addAll(
            "--no-fallback",
            "-H:+ReportExceptionStackTraces",
            "--enable-url-protocols=http,https",
            "-march=compatibility",
            // Netty 4.2 on JDK 25 frees direct buffers through Arena.ofShared (CleanerJava25), by
            // design for native images. GraalVM 25 gates shared arenas behind an experimental flag;
            // without it, buffer release throws UnsupportedFeatureError on event loops (ADR 0008).
            "-H:+UnlockExperimentalVMOptions",
            "-H:+SharedArenaSupport",
            "-H:-UnlockExperimentalVMOptions",
        )
    }
    binaries.named("test") {
        buildArgs.add("-H:+ReportExceptionStackTraces")
    }
}
