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
@LLMDescription("Tools for reading and writing the current scenario snapshot, spec, review, and tests")
class SnapshotAgentTools(
    private val project: Project,
    private val snapshot: ScenarioSnapshot,
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
    @LLMDescription("Submit your work for this turn. Call this when you are done with your role so the orchestrator can hand off to the next agent.")
    fun submit(): String = "Work submitted for scenario '${snapshot.state.scenarioId}'."

    // Keep a reference so tool reflection has a stable host; unused directly.
    @Suppress("unused")
    private val holder: Project = project
}
