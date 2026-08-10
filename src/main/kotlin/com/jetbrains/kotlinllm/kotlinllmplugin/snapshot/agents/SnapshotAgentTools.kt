package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.intellij.openapi.project.Project
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.ScenarioSnapshot

/**
 * Koog [ToolSet] that gives every role agent read/write access to the shared
 * snapshot tree for the scenario being worked on. The orchestrator hands the
 * same [ScenarioSnapshot] instance to all agents, so writes are immediately
 * visible to the next agent in the loop without re-reading from disk.
 *
 * @param snapshot the in-memory scenario being processed (shared across agents).
 */
@Suppress("unused")
@LLMDescription("Tools for reading and writing the current scenario snapshot, spec, review, tests, and the buggy production source")
class SnapshotAgentTools(
    private val project: Project,
    private val snapshot: ScenarioSnapshot,
    /** Absolute path to the buggy production source file the BugFixer edits (null disables source tools). */
    private val sourceFilePath: java.nio.file.Path? = null,
) : ToolSet {

    @Suppress("unused")
    @Tool
    @LLMDescription("Read the current captured state (the materialized snapshot map and observed calls). This is the ground truth for the scenario.")
    fun readState(): String = buildString {
        appendLine("Scenario: ${snapshot.state.scenarioId}")
        appendLine("Captured at: ${snapshot.state.capturedAt.ifBlank { "<unknown>" }}")
        appendLine()
        appendLine("State map:")
        if (snapshot.state.state.isEmpty()) {
            appendLine("  <empty>")
        } else {
            snapshot.state.state.forEach { (k, v) -> appendLine("  $k = $v") }
        }
        appendLine()
        appendLine("Observed calls:")
        if (snapshot.state.observedCalls.isEmpty()) {
            appendLine("  <none>")
        } else {
            snapshot.state.observedCalls.forEachIndexed { i, call ->
                appendLine("  [$i] ${call.methodName}(${call.arguments.entries.joinToString { "${it.key}=${it.value}" }}) : ${call.returnType} -> ${call.returnValue ?: "null"}")
            }
        }
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Discover the project's @MutationTarget classes and their source. Use this to find the real code the tests must exercise (so mutflow discovers mutations instead of 0). Returns each target's package, class name, and full source.")
    fun discoverMutationTargets(): String {
        val base = project.basePath ?: return "<no project base path>"
        val root = java.nio.file.Path.of(base)
        val results = mutableListOf<String>()
        // Scan the main source roots for files declaring @MutationTarget.
        val sourceRoots = listOf(
            root.resolve("src/main/kotlin"),
            root.resolve("kotlin_generated_files"),
        )
        sourceRoots.forEach { srcRoot ->
            if (!srcRoot.toFile().exists()) return@forEach
            srcRoot.toFile().walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    if (text.contains("@MutationTarget") || text.contains("MutationTarget")) {
                        val pkg = Regex("package\\s+([\\w.]+)").find(text)?.groupValues?.get(1) ?: "<default>"
                        val cls = Regex("(?:class|interface|object)\\s+(\\w+)").find(text)?.groupValues?.get(1) ?: "<unknown>"
                        results.add(
                            buildString {
                                appendLine("### $pkg.$cls")
                                appendLine("File: ${file.path}")
                                appendLine("Source:")
                                appendLine(text)
                            }
                        )
                    }
                }
        }
        return if (results.isEmpty()) {
            "<no @MutationTarget classes found in src/main/kotlin or kotlin_generated_files>"
        } else {
            results.joinToString("\n\n")
        }
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Read the buggy production source file. Use this to inspect the code that needs fixing.")
    fun readSource(): String {
        val path = sourceFilePath ?: return "<no source file configured>"
        return runCatching { path.toFile().readText() }.getOrElse { "<could not read $path: ${it.message}>" }
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Write the FULL corrected production source file. Full replacement: pass the complete file text (package, imports, class/object, all functions), not a diff. Preserve public signatures; only change buggy bodies.")
    fun writeSource(
        @LLMDescription("Complete corrected Kotlin source file text.")
        source: String,
    ): String {
        val path = sourceFilePath ?: return "<no source file configured>"
        return runCatching {
            path.toFile().writeText(source.trim() + "\n")
            "Source file updated (${source.length} chars) at $path."
        }.getOrElse { "Failed to write source: ${it.message}" }
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Read the current spec file written by the SpecAuthor (snapshot.spec.kt). Empty if not yet written.")
    fun readSpec(): String = snapshot.spec.ifBlank { "<no spec written yet>" }

    @Suppress("unused")
    @Tool
    @LLMDescription("Read the current review file written by the SpecReviewer (snapshot.review.kt). Empty if not yet written.")
    fun readReview(): String = snapshot.review.ifBlank { "<no review written yet>" }

    @Suppress("unused")
    @Tool
    @LLMDescription("Read the current generated tests (snapshot.test.kt). Empty if not yet generated.")
    fun readTests(): String = snapshot.tests.ifBlank { "<no tests generated yet>" }

    @Suppress("unused")
    @Tool
    @LLMDescription("Read the current coverage report (coverage.md). Empty if mutflow has not run yet.")
    fun readCoverage(): String = snapshot.coverage.ifBlank { "<no coverage report yet>" }

    @Suppress("unused")
    @Tool
    @LLMDescription("Read the current prompt-specification (prompt-spec.md). Empty if SpecWriter has not written it yet.")
    fun readPromptSpec(): String = snapshot.promptSpec.ifBlank { "<no prompt-spec written yet>" }

    @Suppress("unused")
    @Tool
    @LLMDescription("Read the current high-level specification (high-level-spec.md). Empty if CoverageWatchdog has not written it yet.")
    fun readHighLevelSpec(): String = snapshot.highLevelSpec.ifBlank { "<no high-level spec written yet>" }

    @Suppress("unused")
    @Tool
    @LLMDescription("Write the behavior spec (snapshot.spec.kt). Full replacement: pass the complete spec text, not a diff. Must be valid Kotlin containing invariants, pre/post-conditions, or representative input-output mappings derived from the captured state.")
    fun writeSpec(
        @LLMDescription("Complete Kotlin spec text.")
        spec: String,
    ): String {
        snapshot.spec = spec.trim()
        snapshot.dirty = true
        return "Spec updated (${snapshot.spec.length} chars)."
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Write the review (snapshot.review.kt). Full replacement: list uncovered branches, missing inputs, counter-examples, and concrete feedback for the SpecAuthor and TestGenerator.")
    fun writeReview(
        @LLMDescription("Complete review text.")
        review: String,
    ): String {
        snapshot.review = review.trim()
        snapshot.dirty = true
        return "Review updated (${snapshot.review.length} chars)."
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Write the generated tests (snapshot.test.kt). Full replacement: valid Kotlin source containing @MutFlowTest classes with @Test methods wrapping exercised code in MutFlow.underTest { }.")
    fun writeTests(
        @LLMDescription("Complete Kotlin test source.")
        tests: String,
    ): String {
        snapshot.tests = tests.trim()
        snapshot.dirty = true
        return "Tests updated (${snapshot.tests.length} chars)."
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Write the prompt-specification (prompt-spec.md). Full replacement: self-contained entries describing functionality (input, expected output, edge cases, intent) ready to be inlined into code as KDoc.")
    fun writePromptSpec(
        @LLMDescription("Complete prompt-specification markdown text.")
        promptSpec: String,
    ): String {
        snapshot.promptSpec = promptSpec.trim()
        snapshot.dirty = true
        return "Prompt-spec updated (${snapshot.promptSpec.length} chars)."
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Write the high-level specification (high-level-spec.md). Full replacement: a plain-language summary of what the code does, its guarantees, inputs/outputs, and how it is tested.")
    fun writeHighLevelSpec(
        @LLMDescription("Complete high-level specification markdown text.")
        highLevelSpec: String,
    ): String {
        snapshot.highLevelSpec = highLevelSpec.trim()
        snapshot.dirty = true
        return "High-level spec updated (${snapshot.highLevelSpec.length} chars)."
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Submit your work for this turn. Call this when you are done with your role so the orchestrator can hand off to the next agent.")
    fun submit(): String = "Work submitted for scenario '${snapshot.state.scenarioId}'."

    // Keep a reference so tool reflection has a stable host; unused directly.
    @Suppress("unused")
    private val holder: Project = project
}
