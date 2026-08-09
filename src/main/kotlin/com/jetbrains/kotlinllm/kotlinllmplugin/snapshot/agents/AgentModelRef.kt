package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents

import com.jetbrains.kotlinllm.kotlinllmplugin.services.KotlinLlmProvider

/**
 * A reference to a specific LLM provider + model that a role agent should use.
 *
 * This lets each agent run on a different model — e.g. a local Ollama model for
 * cheap spec writing and a cloud Ollama model (like `deepseek-v4-flash:cloud`)
 * for tool-heavy roles such as BugFixer that must reliably call tools to edit code.
 *
 * @param provider the provider family (OpenAI, Grazie, Ollama, Anthropic).
 * @param modelId the model identifier (e.g. `deepseek-v4-flash:cloud`, `claude-sonnet-4-5`).
 * @param baseUrl optional base URL override (used for Ollama).
 */
data class AgentModelRef(
    val provider: KotlinLlmProvider,
    val modelId: String,
    val baseUrl: String? = null,
) {
    /** Stable key used in the `.kotlinllm` config file. */
    val configKey: String
        get() = "${provider.configValue}:$modelId"

    override fun toString(): String = configKey

    companion object {
        /** Parse a `provider:modelId` string (optionally `provider:modelId@baseUrl`). */
        fun parse(raw: String): AgentModelRef? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return null
            val (providerPart, rest) = trimmed.split(':', limit = 2).let {
                if (it.size == 2) it[0] to it[1] else null to it[0]
            }
            val provider = providerPart?.let { KotlinLlmProvider.fromConfigValue(it) }
                ?: KotlinLlmProvider.Ollama
            val (modelId, baseUrl) = rest.split('@', limit = 2).let {
                it[0] to (it.getOrNull(1)?.takeIf { b -> b.isNotBlank() })
            }
            if (modelId.isBlank()) return null
            return AgentModelRef(provider, modelId.trim(), baseUrl)
        }
    }
}
