package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.KoogLlmClient
import com.jetbrains.kotlinllm.kotlinllmplugin.services.KotlinLlmProjectConfig
import com.jetbrains.kotlinllm.kotlinllmplugin.services.readKotlinLlmProjectConfig
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents.AgentModelRef
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents.AgentRole
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents.CustomAgentSpec
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents.SnapshotAgentTools
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.mutflow.MutflowIntegration
import java.nio.file.Path

/**
 * Orchestrates the multi-agent snapshot loop ("the boiler").
 *
 * For each scenario:
 *   1. SNAPSHOTTER materializes/validates the captured state.
 *   2. SPEC_AUTHOR writes the spec.
 *   3. SPEC_REVIEWER reviews it for gaps.
 *   4. TEST_GENERATOR writes @MutFlowTest tests.
 *   5. MUTATION_AUDITOR runs mutflow and reports coverage.
 *   If surviving mutants exist and the coverage improved, loop back to SPEC_AUTHOR
 *   (via reviewer feedback) until coverage plateaus or the max-iteration budget is hit.
 *
 * The loop stops early when: every mutant is killed, coverage stops improving, or the
 * iteration budget is exhausted.
 */
class SnapshotOrchestrator(
    private val project: Project,
    private val statusSink: (String) -> Unit = {},
    private val maxCoverageIterations: Int = 3,
    /** Absolute path to the buggy production source file the BugFixer edits (null disables the BugFixer role). */
    private val sourceFilePath: java.nio.file.Path? = null,
    /** Per-role-agent model overrides, keyed by role display name (e.g. "BugFixer"). */
    private val agentModels: Map<String, String> = emptyMap(),
    /** User-defined custom agents to run after the built-in roles. */
    private val customAgents: List<CustomAgentSpec> = emptyList(),
) {
    private companion object {
        val LOG: Logger = Logger.getInstance(SnapshotOrchestrator::class.java)
        private const val COVERAGE_IMPROVEMENT_THRESHOLD = 0.01
    }

    /**
     * Run the full multi-agent loop for one scenario.
     *
     * @param snapshot the in-memory scenario (state already captured by the caller).
     * @param llmClient a client whose provider/model is already resolved.
     * @param targetProjectDir target project root for mutflow (null skips the mutflow step).
     * @return true if the scenario reached full mutation coverage, false otherwise.
     */
    suspend fun orchestrateScenario(
        snapshot: ScenarioSnapshot,
        llmClient: KoogLlmClient,
        targetProjectDir: Path?,
    ): Boolean {
        val tools = SnapshotAgentTools(project, snapshot, sourceFilePath)

        // --- Pass 1: materialize + author + review + test ---
        runRole(AgentRole.SNAPSHOTTER, snapshot, llmClient, tools, "Materialize the captured state.")
        runRole(AgentRole.SPEC_AUTHOR, snapshot, llmClient, tools, "Author the spec from the captured state.")
        runRole(AgentRole.SPEC_REVIEWER, snapshot, llmClient, tools, "Review the spec for gaps and edge cases.")
        runRole(AgentRole.SPEC_WRITER, snapshot, llmClient, tools, "Write the prompt-specification describing the functionality, ready to be inlined in code.")
        runRole(AgentRole.TEST_GENERATOR, snapshot, llmClient, tools, "Generate @MutFlowTest tests for the spec.")
        runRole(AgentRole.COVERAGE_WATCHDOG, snapshot, llmClient, tools, "Audit the generated code, spec, prompt-spec, and tests; write the high-level specification.")

        // --- BugFixer: repair the production source against the spec (if a source file is configured) ---
        if (sourceFilePath != null) {
            runRole(
                AgentRole.BUG_FIXER, snapshot, llmClient, tools,
                "Read the buggy source and the spec, then write the corrected source back."
            )
        }

        // --- Custom agents (user-defined) run after the built-in roles ---
        customAgents.forEach { agent ->
            runCustomAgent(agent, snapshot, llmClient, tools)
        }

        // --- Coverage loop (only if mutflow integration is enabled) ---
        var previousCoverage = -1.0
        var fullCoverage = false
        if (targetProjectDir != null) {
            // Re-emit the agent-written @MutFlowTest file into the target project's test
            // source set. This both (a) puts fresh agent tests on disk and (b) RECREATES
            // test files the developer removed, so mutflow always runs against the agent's
            // generated suite rather than silently skipping when tests are missing.
            val emittedPath = MutflowIntegration.emitTestFile(
                MutflowIntegration.MutflowRunConfig(
                    project = project,
                    targetProjectDir = targetProjectDir,
                    testPackage = testPackageFor(snapshot),
                ),
                snapshot.state.scenarioId,
                snapshot.tests,
            )
            statusSink(if (emittedPath != null) "Emitted agent tests to $emittedPath." else "Failed to emit agent tests.")

            for (iteration in 1..maxCoverageIterations) {
                statusSink("Running mutflow (iteration $iteration/$maxCoverageIterations)...")
                val report = MutflowIntegration.runMutflow(
                    MutflowIntegration.MutflowRunConfig(
                        project = project,
                        targetProjectDir = targetProjectDir,
                        testPackage = testPackageFor(snapshot),
                    )
                )
                snapshot.coverage = renderCoverage(snapshot, report)
                statusSink(
                    "Coverage: ${report.killed}/${report.total} killed" +
                        if (report.survived > 0) " (${report.survived} survived)" else ""
                )

                // Genuine build/run failure (mutflow couldn't run at all): stop.
                if (!report.succeeded) break

                if (report.total == 0) {
                    // No mutations were measured — the emitted tests are missing, blank, or
                    // don't exercise any @MutationTarget code. Regenerate them via the
                    // TestGenerator agent and retry instead of breaking (this is what recreates
                    // tests a developer removed). Skip on the last iteration to avoid an
                    // infinite regen loop.
                    if (iteration >= maxCoverageIterations) {
                        statusSink("No mutations measured and regeneration budget exhausted; stopping.")
                        break
                    }
                    statusSink("No mutations measured — regenerating tests via TestGenerator.")
                    runRole(
                        AgentRole.TEST_GENERATOR, snapshot, llmClient, tools,
                        "No mutations were measured — the tests are missing or don't exercise @MutationTarget code. Write new @MutFlowTest tests that cover the @MutationTarget class so mutations are discovered."
                    )
                    MutflowIntegration.emitTestFile(
                        MutflowIntegration.MutflowRunConfig(
                            project = project,
                            targetProjectDir = targetProjectDir,
                            testPackage = testPackageFor(snapshot),
                        ),
                        snapshot.state.scenarioId,
                        snapshot.tests,
                    )
                    previousCoverage = 0.0
                    continue
                }

                if (report.survived == 0) {
                    fullCoverage = true
                    runRole(
                        AgentRole.MUTATION_AUDITOR, snapshot, llmClient, tools,
                        "Coverage is complete (all mutants killed). Confirm and summarize."
                    )
                    break
                }

                val coverage = report.coverage
                val improved = coverage - previousCoverage >= COVERAGE_IMPROVEMENT_THRESHOLD
                if (iteration > 1 && !improved) {
                    statusSink("Coverage plateaued at ${(coverage * 100).toInt()}%. Stopping loop.")
                    break
                }
                previousCoverage = coverage

                // Hand surviving-mutant recommendations back to the spec/test agents.
                runRole(
                    AgentRole.MUTATION_AUDITOR, snapshot, llmClient, tools,
                    "Report surviving mutants and recommend tests/spec changes to close the highest-value gap."
                )
                runRole(
                    AgentRole.SPEC_AUTHOR, snapshot, llmClient, tools,
                    "Incorporate reviewer and auditor feedback to cover the surviving mutants."
                )
                runRole(
                    AgentRole.TEST_GENERATOR, snapshot, llmClient, tools,
                    "Regenerate tests to kill the surviving mutants."
                )
            }
        }

        // --- Persist the final materialized snapshot ---
        SnapshotIo.write(project, snapshot)
        statusSink("Materialized snapshot for '${snapshot.state.scenarioId}'.")
        return fullCoverage
    }

    private suspend fun runRole(
        role: AgentRole,
        snapshot: ScenarioSnapshot,
        llmClient: KoogLlmClient,
        tools: SnapshotAgentTools,
        directive: String,
    ) {
        statusSink("${role.displayName}: starting...")
        val system = buildString {
            appendLine(role.persona)
            appendLine()
            appendLine(
                "Scenario id: ${snapshot.state.scenarioId}. " +
                    "Use the provided tools to read the snapshot/spec/review and write your artifact. " +
                    "Call submit() when finished."
            )
        }
        val user = buildString {
            appendLine(directive)
            appendLine()
            appendLine("Current scenario state:")
            appendLine(tools.readState())
        }
        val modelOverride = agentModels[role.displayName]?.let { AgentModelRef.parse(it) }
        if (modelOverride != null) {
            statusSink("${role.displayName}: using model ${modelOverride.configKey}")
        }
        // The chat() method wires the Koog AIAgent with the SnapshotAgentTools registry.
        runCatching {
            llmClient.chatWithTools(system, user, tools, modelOverride)
        }.onSuccess {
            statusSink("${role.displayName}: done.")
        }.onFailure { e ->
            statusSink("${role.displayName}: failed (${e.message}). Continuing with what was written.")
            LOG.warn("${role.displayName} failed", e)
        }
    }

    private suspend fun runCustomAgent(
        agent: CustomAgentSpec,
        snapshot: ScenarioSnapshot,
        llmClient: KoogLlmClient,
        tools: SnapshotAgentTools,
    ) {
        statusSink("${agent.name}: starting...")
        val system = buildString {
            appendLine(agent.persona)
            appendLine()
            appendLine(
                "Scenario id: ${snapshot.state.scenarioId}. " +
                    "Use the provided tools to read the snapshot/spec/review and write your artifact. " +
                    "Call submit() when finished."
            )
        }
        val user = buildString {
            appendLine("Perform your role for this scenario.")
            appendLine()
            appendLine("Current scenario state:")
            appendLine(tools.readState())
        }
        if (agent.model != null) {
            statusSink("${agent.name}: using model ${agent.model.configKey}")
        }
        runCatching {
            llmClient.chatWithTools(system, user, tools, agent.model)
        }.onSuccess {
            statusSink("${agent.name}: done.")
        }.onFailure { e ->
            statusSink("${agent.name}: failed (${e.message}). Continuing with what was written.")
            LOG.warn("${agent.name} failed", e)
        }
    }

    private fun testPackageFor(snapshot: ScenarioSnapshot): String {
        // Derive a package from the scenario id; fall back to a default.
        val base = snapshot.state.scenarioId
            .substringBeforeLast('.')
            .map { if (it.isLetterOrDigit() || it == '.') it else '_' }
            .joinToString("")
            .trim('.')
        return if (base.isBlank()) "generated.snapshot" else "generated.snapshot.$base"
    }

    private fun renderCoverage(snapshot: ScenarioSnapshot, report: MutflowIntegration.MutflowReport): String = buildString {
        appendLine("# Mutation coverage — ${snapshot.state.scenarioId}")
        appendLine()
        appendLine("Total mutants: ${report.total}")
        appendLine("Killed: ${report.killed}")
        appendLine("Survived: ${report.survived}")
        appendLine("Coverage: ${(report.coverage * 100).toInt()}%")
        appendLine()
        if (report.total == 0) {
            // No mutations were measured (e.g. empty/missing agent tests, or no
            // @MutationTarget code exercised). This is NOT a green run.
            appendLine("**No mutations measured** — the agent tests did not exercise any @MutationTarget code. This is not full coverage.")
        } else if (report.survivedMutants.isNotEmpty()) {
            appendLine("## Surviving mutants (coverage gaps)")
            appendLine()
            report.survivedMutants.forEach { m ->
                appendLine("- ${m.line}${m.operator?.let { " ($it)" }.orEmpty()} — ${m.details.ifBlank { m.status }}")
            }
        } else {
            appendLine("All mutants killed — no coverage gaps.")
        }
    }
}
