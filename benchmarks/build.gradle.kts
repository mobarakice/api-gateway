// JMH microbenchmarks. Not part of `build`'s test run; execute explicitly:
//   ./gradlew :benchmarks:jmh                                   all benchmarks, default settings
//   ./gradlew :benchmarks:jmh -Pjmh.args="RouterBenchmark -prof gc -f 1"
// Results: benchmarks/build/results/jmh/results.json. Numbers from the dev host are labelled as such.
plugins {
    id("gw.java-conventions")
    id("gw.quality-conventions")
}

dependencies {
    implementation(project(":gateway-core"))
    implementation(project(":gateway-proxy"))
    implementation(project(":gateway-plugins"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator)
}

// JMH-generated sources trip Checkstyle/SpotBugs rules written for production code.
tasks.named("spotbugsMain") { enabled = false }
tasks.named<Checkstyle>("checkstyleMain") { exclude("**/jmh_generated/**") }

val resultsDir = layout.buildDirectory.dir("results/jmh")
tasks.register<JavaExec>("jmh") {
    description = "Runs JMH benchmarks (args via -Pjmh.args)."
    group = "benchmark"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    val extra = providers.gradleProperty("jmh.args").map { it.split(" ").filter(String::isNotBlank) }
        .orElse(emptyList())
    val out = resultsDir
    argumentProviders.add(CommandLineArgumentProvider {
        extra.get() + listOf("-rf", "json", "-rff", out.get().file("results.json").asFile.absolutePath)
    })
    doFirst { out.get().asFile.mkdirs() }
}
