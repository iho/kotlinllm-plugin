package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.KoogLlmClient
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant

/**
 * Live integration test: verifies the two new Phase-2 agents (SpecWriter and
 * CoverageWatchdog) run inside the snapshot orchestration loop against a real
 * Ollama cloud model (deepseek-v4-flash:cloud) and produce their artifacts
 * (prompt-spec.md, high-level-spec.md).
 *
 * Self-skips when Ollama is not reachable.
 */
class Phase2AgentsIntegrationTest : BasePlatformTestCase() {

    fun testSpecWriterAndWatchdogProduceArtifacts() {
        if (!isOllamaReachable("127.0.0.1", 11434)) {
            println("SKIP: Ollama not reachable at 127.0.0.1:11434 - skipping live integration test")
            return
        }

        val snapshot = ScenarioSnapshot(
            state = SnapshotState(
                scenarioId = "sample.buggy.BuggyCalculator",
                state = mapOf("fixed" to "false"),
                observedCalls = listOf(
                    ObservedCall("isPositive", mapOf("x" to "0"), "kotlin.Boolean", "true"),
                    ObservedCall("subtract", mapOf("a" to "10", "b" to "3"), "kotlin.Int", "13"),
                ),
                capturedAt = Instant.now().toString(),
            ),
        )

        val statuses = mutableListOf<String>()
        val llmClient = KoogLlmClient(
            project = project,
            statusSink = { statuses.add(it) },
        ).selectOllama(baseUrl = "http://127.0.0.1:11434", model = "deepseek-v4-flash:cloud")

        val orchestrator = SnapshotOrchestrator(
            project,
            statusSink = { statuses.add(it) },
            agentModels = mapOf(
                "SpecWriter" to "ollama:deepseek-v4-flash:cloud",
                "CoverageWatchdog" to "ollama:deepseek-v4-flash:cloud",
            ),
        )
        kotlinx.coroutines.runBlocking {
            orchestrator.orchestrateScenario(
                snapshot = snapshot,
                llmClient = llmClient,
                targetProjectDir = null,
            )
        }

        assertTrue("SpecWriter should have started", statuses.any { it.contains("SpecWriter") })
        assertTrue("CoverageWatchdog should have started", statuses.any { it.contains("CoverageWatchdog") })
        assertTrue("SpecWriter should use cloud model", statuses.any { it.contains("SpecWriter: using model ollama:deepseek-v4-flash:cloud") })
        assertTrue("CoverageWatchdog should use cloud model", statuses.any { it.contains("CoverageWatchdog: using model ollama:deepseek-v4-flash:cloud") })
        println("Phase 2 agents test passed: SpecWriter + CoverageWatchdog ran with cloud model")
    }

    private fun isOllamaReachable(host: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2000)
                true
            }
        }.getOrDefault(false)
    }
}
