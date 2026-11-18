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
            // netty-codec-classes-quic is on the classpath only for Vert.x's `instanceof QuicChannel`
            // check. Nothing in it may initialise at build time: BoringSSL's <clinit> needs the native
            // QUIC library, which is excluded (no HTTP/3, ADR 0003).
            "--initialize-at-run-time=io.netty.handler.codec.quic",
            // Netty's SslContext holds a static SecureRandom. Initialised at build time, its seed would be
            // baked into the binary; Native Image refuses that once the class is reachable (it is, via
            // Vert.x's TLS context providers). Run-time init gives each process a fresh seed.
            "--initialize-at-run-time=io.netty.handler.ssl.SslContext",
        )
    }
    binaries.named("test") {
        buildArgs.add("-H:+ReportExceptionStackTraces")
    }
}
