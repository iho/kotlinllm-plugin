package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.mutflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.jetbrains.kotlinllm.kotlinllmplugin.services.resolveConfiguredKotlinLlmFolder
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.SnapshotFiles
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.json.MiniJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.io.path.name

/**
 * Emits @MutFlowTest source into the target project's test source set and invokes
 * mutflow via Gradle, then parses the coverage result into a [MutflowReport].
 *
 * The generated test file is written to the target project's
 * `src/test/kotlin/<package path>/<scenario>.kt`, mirroring how a developer would
 * add a mutflow test by hand. The mutflow Gradle task is invoked with
 * `./gradlew mutflowTest` (or the configured task name).
 */
object MutflowIntegration {

    /** Default mutflow Gradle task name. Override via [MutflowRunConfig.taskName]. */
    const val DEFAULT_MUTFLOW_TASK = "test"

    data class MutflowRunConfig(
        val project: Project,
        /** Path to the target project root (where gradlew lives). */
        val targetProjectDir: Path,
        /** Package of the generated test. */
        val testPackage: String = "generated.snapshot",
        /** Mutflow/test Gradle task to run. */
        val taskName: String = DEFAULT_MUTFLOW_TASK,
        /** Timeout for the gradle invocation, in milliseconds. */
        val timeoutMs: Long = 120_000,
    )

    data class Mutant(
        val line: String,
        val operator: String?,
        val status: String, // "KILLED" | "SURVIVED" | "NO_COVERAGE"
        val details: String = "",
    )

    data class MutflowReport(
        val total: Int,
        val killed: Int,
        val survived: Int,
        val mutants: List<Mutant>,
        val rawOutput: String,
        val succeeded: Boolean,
    ) {
        val survivedMutants: List<Mutant> get() = mutants.filter { it.status == "SURVIVED" }
        /** Coverage ratio (0.0–1.0). Returns 0.0 when no mutations were measured, so an
         *  empty run is NOT reported as 100% coverage (a false green). */
        val coverage: Double get() = if (total == 0) 0.0 else killed.toDouble() / total
    }

    /**
     * Write the generated test source into the target project and refresh the VFS.
     * Returns the absolute path written, or null on failure.
     */
    fun emitTestFile(
        config: MutflowRunConfig,
        scenarioId: String,
        testSource: String,
    ): Path? {
        val testDir = config.targetProjectDir
            .resolve("src").resolve("test").resolve("kotlin")
            .resolve(config.testPackage.replace('.', '/'))
        runCatching { testDir.createDirectories() }.getOrNull() ?: return null

        val fileName = "${scenarioId.sanitized()}.kt"
        val file = testDir.resolve(fileName)
        runCatching { file.writeText(testSource) }.getOrNull() ?: return null

        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(testDir)?.refresh(false, true)
        return file
    }

    /**
     * Run mutflow via Gradle and parse the result. Blocks on the gradle process.
     */
    fun runMutflow(config: MutflowRunConfig): MutflowReport {
        val gradlew = config.targetProjectDir.resolve("gradlew")
        if (!gradlew.exists()) {
            return MutflowReport(
                total = 0, killed = 0, survived = 0, mutants = emptyList(),
                rawOutput = "No gradlew found at ${gradlew}", succeeded = false,
            )
        }

        val command = listOf(gradlew.toString(), config.taskName, "--console=plain")
        val output = try {
            runProcess(command, config.targetProjectDir, config.timeoutMs)
        } catch (e: Exception) {
            return MutflowReport(
                total = 0, killed = 0, survived = 0, mutants = emptyList(),
                rawOutput = "Gradle run failed: ${e.message}", succeeded = false,
            )
        }

        return parseReport(output)
    }

    private fun runProcess(command: List<String>, workDir: Path, timeoutMs: Long): String {
        val pb = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
        // Ask mutflow to emit its machine-readable JSON summary line.
        pb.environment().put("MUTFLOW_JSON_OUTPUT", "true")
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("Gradle task timed out after ${timeoutMs}ms")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException("Gradle task exited with ${process.exitValue()}")
        }
        return output
    }

    /**
     * Parse mutflow console output.
     *
     * Prefers the structured `[mutflow-json]` summary line emitted by mutflow when
     * `MUTFLOW_JSON_OUTPUT=true` (each mutant: pointId, variantIndex, display, status,
     * killedBy). Falls back to the legacy per-mutant `MUTANT status=...` lines, then to
     * a generic killed/survived counter.
     */
    fun parseReport(rawOutput: String): MutflowReport {
        parseJsonReports(rawOutput)?.let { return it }

        val structured = Regex(
            """(?i)MUTANT\s+status=(\w+)\s+class=(\S+)\s+line=(\d+)(?:\s+operator=(\S+))?"""
        ).findAll(rawOutput).toList()

        if (structured.isNotEmpty()) {
            val mutants = structured.map { m ->
                Mutant(
                    line = m.groupValues[2] + ":" + m.groupValues[3],
                    operator = m.groupValues[4].takeIf { it.isNotEmpty() },
                    status = m.groupValues[1].uppercase(),
                )
            }
            val killed = mutants.count { it.status == "KILLED" }
            val survived = mutants.count { it.status == "SURVIVED" || it.status == "NO_COVERAGE" }
            return MutflowReport(
                total = mutants.size, killed = killed, survived = survived,
                mutants = mutants, rawOutput = rawOutput, succeeded = true,
            )
        }

        // Fallback: parse "KILLED <n>" / "SURVIVED <n>" lines.
        val killed = extractNumeric(rawOutput, "KILLED")
        val survived = extractNumeric(rawOutput, "SURVIVED")
        val total = killed + survived
        return MutflowReport(
            total = total, killed = killed, survived = survived,
            mutants = emptyList(), rawOutput = rawOutput,
            succeeded = total > 0 || rawOutput.contains("BUILD SUCCESSFUL"),
        )
    }

    /**
     * Parses all `[mutflow-json] ...` lines and aggregates them into a single report.
     * Returns null if no such lines are present. Each line is one session's summary; a
     * full test suite emits several, so totals are summed and mutant lists concatenated.
     */
    private fun parseJsonReports(rawOutput: String): MutflowReport? {
        val lines = rawOutput.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("[mutflow-json]") }
            .toList()
        if (lines.isEmpty()) return null

        val parsed = lines.mapNotNull { line ->
            runCatching {
                MiniJson.Value.parse(line.removePrefix("[mutflow-json]").trim()) as? MiniJson.Value.Obj
            }.getOrNull()
        }
        if (parsed.isEmpty()) return null

        val mutants = mutableListOf<Mutant>()
        var total = 0
        var killed = 0
        var survived = 0
        for (obj in parsed) {
            total += obj.int("totalMutations") ?: 0
            killed += obj.int("killed") ?: 0
            survived += (obj.int("survived") ?: 0) + (obj.int("timedOut") ?: 0)
            for (item in obj.arr("mutants")) {
                val m = item as? MiniJson.Value.Obj ?: continue
                val status = m.str("status") ?: continue
                val display = m.str("display").orEmpty()
                val pointId = m.str("pointId").orEmpty()
                val killedBy = m.str("killedBy")
                mutants.add(
                    Mutant(
                        line = pointId,
                        operator = display.substringAfter("→").trim().ifBlank { null },
                        status = status.uppercase(),
                        details = display + (killedBy?.let { " (killed by $it)" }.orEmpty()),
                    )
                )
            }
        }
        return MutflowReport(
            total = total, killed = killed, survived = survived,
            mutants = mutants, rawOutput = rawOutput, succeeded = true,
        )
    }

    private fun extractNumeric(output: String, label: String): Int {
        val regex = Regex("""(?i)${Regex.escape(label)}\s*[=:]?\s*(\d+)""")
        return regex.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun String.sanitized(): String =
        map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")
}
