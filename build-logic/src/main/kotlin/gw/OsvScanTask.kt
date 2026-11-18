package gw

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Dependency vulnerability scan against the OSV database (https://osv.dev), which aggregates
 * GitHub Security Advisories and NVD data for the Maven ecosystem. Fails the build on any known
 * vulnerability that is not listed, with a reason, in the allowlist. See docs/adr/0010.
 */
@DisableCachingByDefault(because = "Advisory data changes independently of inputs")
abstract class OsvScanTask : DefaultTask() {

    @get:Input
    abstract val coordinates: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allowlist: RegularFileProperty

    @get:Input
    abstract val endpoint: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun scan() {
        val coords = coordinates.get().distinct().sorted()
        val allowed = allowlist.get().asFile.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val findings = mutableListOf<String>()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        coords.chunked(500).forEach { chunk ->
            val queries = chunk.map { c ->
                val parts = c.split(':')
                mapOf(
                    "package" to mapOf("name" to "${parts[0]}:${parts[1]}", "ecosystem" to "Maven"),
                    "version" to parts[2],
                )
            }
            val request = HttpRequest.newBuilder(URI.create(endpoint.get()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(mapOf("queries" to queries))))
                .build()
            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: java.io.IOException) {
                throw GradleException(
                    "OSV dependency scan could not reach ${endpoint.get()}. The scan is a required " +
                        "quality gate; rerun with network access.", e)
            }
            if (response.statusCode() != 200) {
                throw GradleException("OSV dependency scan failed: HTTP ${response.statusCode()}")
            }
            @Suppress("UNCHECKED_CAST")
            val results = (JsonSlurper().parseText(response.body()) as Map<String, Any?>)["results"]
                as List<Map<String, Any?>>
            results.forEachIndexed { i, result ->
                @Suppress("UNCHECKED_CAST")
                val vulns = result["vulns"] as List<Map<String, Any?>>? ?: emptyList()
                vulns.map { it["id"] as String }
                    .filterNot { it in allowed }
                    .forEach { findings += "${chunk[i]} -> $it (https://osv.dev/vulnerability/$it)" }
            }
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            "Scanned ${coords.size} runtime components\n" +
                (if (findings.isEmpty()) "No unallowlisted vulnerabilities\n" else findings.joinToString("\n", postfix = "\n")))
        if (findings.isNotEmpty()) {
            throw GradleException(
                "Known vulnerabilities in runtime dependencies:\n  " + findings.joinToString("\n  ") +
                    "\nUpgrade, or add the ID with a reason to config/osv-allowlist.txt after review.")
        }
    }
}
