// Quality gates: Checkstyle, SpotBugs, dependency vulnerability scan. Violations fail the build.
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    java
    checkstyle
    id("com.github.spotbugs")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val rootDir = rootProject.layout.projectDirectory

checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    configFile = rootDir.file("config/checkstyle/checkstyle.xml").asFile
    isIgnoreFailures = false
    maxWarnings = 0
}

spotbugs {
    toolVersion = libs.findVersion("spotbugs").get().requiredVersion
    ignoreFailures = false
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.MEDIUM
    excludeFilter = rootDir.file("config/spotbugs/exclude.xml")
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required = true }
}

val osvScan = tasks.register<gw.OsvScanTask>("dependencyScan") {
    group = "verification"
    description = "Scans runtime dependencies against the OSV vulnerability database."
    val runtime = configurations.named("runtimeClasspath")
    coordinates = runtime.flatMap { it.incoming.artifacts.resolvedArtifacts }.map { artifacts ->
        artifacts.mapNotNull { a ->
            (a.id.componentIdentifier as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}:${it.version}" }
        }
    }
    allowlist = rootDir.file("config/osv-allowlist.txt")
    endpoint = providers.gradleProperty("gw.osvEndpoint").orElse("https://api.osv.dev/v1/querybatch")
    report = layout.buildDirectory.file("reports/dependency-scan/osv.txt")
}

tasks.named("check") { dependsOn(osvScan) }
