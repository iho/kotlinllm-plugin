package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.KoogLlmClient
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents.CustomAgentSpec
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Live integration test: verifies per-agent model overrides and custom agents.
 *
 * The BugFixer role is configured to use a cloud Ollama model
 * (deepseek-v4-flash:cloud) while the other roles use the local default. This proves
 * tool-heavy roles can be pointed at a cloud model that reliably calls tools to edit
 * code, even when the local model cannot.
 *
 * Self-skips when Ollama is not reachable.
 */
class PerAgentModelIntegrationTest : BasePlatformTestCase() {

    fun testBugFixerUsesCloudModelAndEditsSource() {
        if (!isOllamaReachable("127.0.0.1", 11434)) {
            println("SKIP: Ollama not reachable at 127.0.0.1:11434 - skipping live integration test")
            return
        }

        val sampleSource = Path.of(
            "examples/buggy-sample/src/main/kotlin/sample/buggy/BuggyCalculator.kt"
        ).toAbsolutePath()
        assertTrue("sample source missing: $sampleSource", Files.exists(sampleSource))
        val original = Files.readString(sampleSource)

        val tempDir = Files.createTempDirectory("peragent-test")
        val buggyCopy = tempDir.resolve("BuggyCalculator.kt")
        Files.writeString(buggyCopy, original)

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
        ).selectOllama(baseUrl = "http://127.0.0.1:11434", model = "llama3.2:latest")

        // BugFixer uses the cloud model; a custom agent also uses the cloud model.
        val orchestrator = SnapshotOrchestrator(
            project,
            statusSink = { statuses.add(it) },
            sourceFilePath = buggyCopy,
            agentModels = mapOf("BugFixer" to "ollama:deepseek-v4-flash:cloud"),
            customAgents = listOf(
                CustomAgentSpec(
                    name = "DocWriter",
                    persona = "You are DocWriter. Read the spec and write a short summary of the scenario into the review file.",
                    model = com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents.AgentModelRef.parse("ollama:deepseek-v4-flash:cloud"),
                )
            ),
        )
        kotlinx.coroutines.runBlocking {
            orchestrator.orchestrateScenario(
                snapshot = snapshot,
                llmClient = llmClient,
                targetProjectDir = null,
            )
        }

        // BugFixer should have run with the cloud model override.
        assertTrue("BugFixer should have started", statuses.any { it.contains("BugFixer") })
        assertTrue(
            "BugFixer should log cloud model usage",
            statuses.any { it.contains("BugFixer: using model ollama:deepseek-v4-flash:cloud") }
        )
        // Custom agent should have run with its cloud model.
        assertTrue("DocWriter should have started", statuses.any { it.contains("DocWriter") })
        assertTrue(
            "DocWriter should log cloud model usage",
            statuses.any { it.contains("DocWriter: using model ollama:deepseek-v4-flash:cloud") }
        )
        // BugFixer should have edited the source.
        val edited = Files.readString(buggyCopy)
        assertTrue("BugFixer should have modified the source", edited != original)
        println("Per-agent model test: BugFixer + DocWriter used cloud model, source edited (${edited.length} chars)")
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
