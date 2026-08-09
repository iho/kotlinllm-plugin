package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents

/**
 * A user-defined agent that can be added to the snapshot orchestration loop.
 *
 * Custom agents are declared in the `.kotlinllm` config file under the
 * `customAgents` key as a JSON array. Each entry has a name, a persona (system
 * prompt), an optional model reference, and an optional list of tool names it is
 * allowed to call. If no model is given, the agent uses the project's default
 * provider/model.
 *
 * Example `.kotlinllm` entry:
 * ```
 * customAgents=[{"name":"DocWriter","persona":"You write concise Kotlin docs.","model":"ollama:deepseek-v4-flash:cloud","tools":["readSpec","writeSpec"]}]
 * ```
 */
data class CustomAgentSpec(
    val name: String,
    val persona: String,
    val model: AgentModelRef? = null,
    val tools: List<String> = emptyList(),
)
