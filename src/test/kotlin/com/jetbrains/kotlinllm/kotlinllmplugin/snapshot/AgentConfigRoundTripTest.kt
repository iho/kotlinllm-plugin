package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.jetbrains.kotlinllm.kotlinllmplugin.services.KotlinLlmProjectConfig
import com.jetbrains.kotlinllm.kotlinllmplugin.services.readKotlinLlmProjectConfig
import com.jetbrains.kotlinllm.kotlinllmplugin.services.saveKotlinLlmProjectConfig
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Verifies the per-agent model + custom agent config round-trips through the
 * public save/read API, matching the exact format written to a project's
 * `.kotlinllm` file.
 */
class AgentConfigRoundTripTest : BasePlatformTestCase() {

    fun testCustomAgentsAndAgentModelsRoundTrip() {
        val config = KotlinLlmProjectConfig(
            generatedFolder = "kotlin_generated_files",
            llmProvider = com.jetbrains.kotlinllm.kotlinllmplugin.services.KotlinLlmProvider.Ollama,
            buildsFolder = "kotlin_build_files",
            ollamaBaseUrl = "http://localhost:11434",
            ollamaModel = "gemma4:26b",
            agentModels = mapOf(
                "BugFixer" to "ollama:deepseek-v4-flash:cloud",
                "SpecAuthor" to "ollama:deepseek-v4-flash:cloud",
            ),
            customAgents = listOf(
                com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents.CustomAgentSpec(
                    name = "DocWriter",
                    persona = "You are DocWriter. Read the spec and write a short summary of the scenario into the review file.",
                    model = com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents.AgentModelRef.parse("ollama:deepseek-v4-flash:cloud"),
                )
            ),
        )

        saveKotlinLlmProjectConfig(project, config)
        val readBack = readKotlinLlmProjectConfig(project)

        assertEquals("BugFixer model", "ollama:deepseek-v4-flash:cloud", readBack.agentModels["BugFixer"])
        assertEquals("SpecAuthor model", "ollama:deepseek-v4-flash:cloud", readBack.agentModels["SpecAuthor"])
        assertEquals("custom agent count", 1, readBack.customAgents.size)
        val docWriter = readBack.customAgents[0]
        assertEquals("DocWriter name", "DocWriter", docWriter.name)
        assertTrue("DocWriter persona preserved", docWriter.persona.contains("short summary"))
        assertEquals("DocWriter model", "ollama:deepseek-v4-flash:cloud", docWriter.model?.configKey)
    }
}
