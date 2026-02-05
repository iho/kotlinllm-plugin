package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm

import ai.grazie.model.cloud.AuthType
import ai.jetbrains.code.prompt.executor.clients.grazie.koog.createGraziePromptExecutor
import ai.jetbrains.code.prompt.executor.clients.grazie.koog.model.GrazieEnvironment
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.intellij.openapi.project.Project
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualType
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualValue
import com.jetbrains.kotlinllm.kotlinllmplugin.services.KotlinLlmProvider
import com.jetbrains.kotlinllm.kotlinllmplugin.services.elapsedMillis
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmStatsService
import com.jetbrains.kotlinllm.kotlinllmplugin.services.readConfiguredKotlinLlmApiKey
import com.jetbrains.kotlinllm.kotlinllmplugin.services.readConfiguredKotlinLlmProvider
import java.util.concurrent.atomic.AtomicInteger

internal const val AGENT_INSPECTION_TOOL_BUDGET = 12
private const val AGENT_TOOL_BUDGET_WARNING_THRESHOLD = 5

/**
 * LLM client implementation using JetBrains Koog framework.
 * Provides an AI agent with tools for inspecting runtime values and submitting generated updates.
 */
class KoogLlmClient(
    private val apiToken: String? = null,
    private val project: Project? = null,
    private val statusSink: (String) -> Unit = {},
    private var llmProvider: KotlinLlmProvider? = null,
) : LlmClient {
    data class AsLlmCaseUpdate(
        val guardExpression: String,
        val caseHandlerBody: String,
    )

    var submittedCase: AsLlmCaseUpdate? = null
    var fromValues: List<ActualValue> = emptyList()
    var toType: ActualType? = null
    var previousError: String? = null
    var previousSubmittedCase: AsLlmCaseUpdate? = null
    var statsMode: String = "unknown"
    internal val toolbeltFunctions: LinkedHashMap<String, String> = linkedMapOf()
    internal val projectRef: Project? get() = project
    private var inspectionToolCalls: Int = 0

    fun setToolbeltFunctions(functions: List<String>) {
        toolbeltFunctions.clear()
        functions.forEach { functionText ->
            val name = TOOLBELT_FUNCTION_NAME_REGEX
                .find(functionText)
                ?.groupValues
                ?.getOrNull(1)
                ?: return@forEach
            toolbeltFunctions[name] = functionText.trim()
        }
    }

    fun toolbeltFunctionTexts(): List<String> = toolbeltFunctions.values.toList()

    fun selectOpenAI(): KoogLlmClient {
        llmProvider = KotlinLlmProvider.OpenAI
        return this
    }

    fun selectGrazie(): KoogLlmClient {
        llmProvider = KotlinLlmProvider.Grazie
        return this
    }

    override suspend fun chat(system: String, user: String): String {
        val startedNanos = System.nanoTime()
        val toolCalls = AtomicInteger(0)
        val stats = project?.kotlinLlmStatsService
        val provider = resolveLlmProvider()
        val executor = createPromptExecutor(provider, resolveApiToken(provider))
        val llmModel = llmModel(provider)
        inspectionToolCalls = 0

        val parserTools = KoogParserTools(this)

        val toolRegistry = ToolRegistry {
            tools(parserTools)
        }

        val agent = AIAgent(
            executor = executor,
            systemPrompt = system,
            llmModel = llmModel,
            toolRegistry = toolRegistry,
            maxIterations = 222,
        ) {
            handleEvents {
                onBeforeLLMCall {
                    if (submittedCase != null) {
                        reportStatus("Submitting succeeded.")
                        throw SubmittedCaseEarlyExit()
                    }
                }

                onToolCall { eventContext ->
                    toolCalls.incrementAndGet()
                    logAgentEvent("Tool called: ${eventContext.tool.name}")
                }

                onToolValidationError { eventContext ->
                    if (eventContext.tool.name == "submitCase") {
                        logAgentEvent("Tool validation failed: submitCase")
                    } else {
                        logAgentEvent(
                            "Tool validation failed: ${eventContext.tool.name}: ${eventContext.error}"
                        )
                    }
                }

                onToolCallFailure { eventContext ->
                    val cause = eventContext.throwable.rootCause()
                    if (eventContext.tool.name == "submitCase") {
                        logAgentEvent("Tool failed: submitCase")
                    } else {
                        logAgentEvent(
                            "Tool failed: ${eventContext.tool.name}: " +
                                "${cause::class.simpleName}: ${cause.message ?: "no details"}"
                        )
                    }
                }

                onToolCallResult { eventContext ->
                    val resultText = eventContext.result
                        ?.let { eventContext.tool.encodeResultToStringUnsafe(it) }
                        ?: "<no result>"
                    if (eventContext.tool.name != "submitCase") {
                        logAgentEvent("Tool result: ${eventContext.tool.name}: ${resultText.compactForLog()}")
                    }
                }

                onAgentFinished { eventContext ->
                    logAgentEvent("Finished with result: ${eventContext.result}")
                }
            }
        }

        return try {
            val result = agent.run(user)
            stats?.recordLlmRequestCompleted(
                mode = statsMode,
                success = true,
                durationMs = elapsedMillis(startedNanos, System.nanoTime()),
                toolCalls = toolCalls.get(),
                responseLength = result.length,
            )
            result
        } catch (error: Throwable) {
            if (error is SubmittedCaseEarlyExit && submittedCase != null) {
                stats?.recordLlmRequestCompleted(
                    mode = statsMode,
                    success = true,
                    durationMs = elapsedMillis(startedNanos, System.nanoTime()),
                    toolCalls = toolCalls.get(),
                    responseLength = SUBMITTED_CASE_RESPONSE.length,
                )
                return SUBMITTED_CASE_RESPONSE
            }
            stats?.recordLlmRequestCompleted(
                mode = statsMode,
                success = false,
                durationMs = elapsedMillis(startedNanos, System.nanoTime()),
                toolCalls = toolCalls.get(),
                responseLength = 0,
                error = "${error::class.simpleName}: ${error.message ?: "no details"}",
            )
            throw error
        }
    }

    internal fun reportStatus(message: String) {
        statusSink(message)
        logAgentEvent(message)
    }

    internal fun logSubmittedCase(guardExpression: String, caseHandlerBody: String) {
        logAgentEvent(
            buildString {
                appendLine("Submitting case:")
                appendLine("guardExpression:")
                appendLine(guardExpression)
                appendLine("caseHandlerBody:")
                appendLine(caseHandlerBody)
            }.trimEnd()
        )
    }

    internal fun recordInspectionToolUse(toolName: String): String? {
        inspectionToolCalls += 1
        val remaining = AGENT_INSPECTION_TOOL_BUDGET - inspectionToolCalls
        return when {
            remaining < 0 -> {
                val message = "Inspection tool budget exceeded. Stop reading and submit with the facts already gathered."
                reportStatus(message)
                message
            }
            remaining <= AGENT_TOOL_BUDGET_WARNING_THRESHOLD -> {
                val message = "Inspection tool budget: $remaining calls left after $toolName."
                reportStatus(message)
                message
            }
            else -> null
        }
    }

    private fun logAgentEvent(message: String) {
        println("KotlinLLM Agent: $message")
    }

    private fun String.compactForLog(limit: Int = 240): String {
        val compact = replace(Regex("""\s+"""), " ").trim()
        return if (compact.length <= limit) compact else compact.take(limit).trimEnd() + "..."
    }

    private fun Throwable.rootCause(): Throwable {
        var current = this
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun resolveLlmProvider(): KotlinLlmProvider {
        return llmProvider ?: project?.let(::readConfiguredKotlinLlmProvider) ?: KotlinLlmProvider.OpenAI
    }

    private fun resolveApiToken(provider: KotlinLlmProvider): String {
        apiToken?.takeIf { it.isNotBlank() }?.let { return it }
        project?.let(::readConfiguredKotlinLlmApiKey)?.let { return it }
        if (provider == KotlinLlmProvider.Grazie) {
            System.getenv("GRAZIE_JWT_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val credentialName = when (provider) {
            KotlinLlmProvider.OpenAI -> "OpenAI API key"
            KotlinLlmProvider.Grazie -> "Grazie JWT token"
        }
        error("KotlinLLM $credentialName is not set. Add apiKey to .kotlinllm from Tools > KotlinLLM Settings.")
    }

    private suspend fun createPromptExecutor(provider: KotlinLlmProvider, apiToken: String): PromptExecutor {
        return when (provider) {
            KotlinLlmProvider.OpenAI -> SingleLLMPromptExecutor(OpenAILLMClient(apiToken))
            KotlinLlmProvider.Grazie -> createGraziePromptExecutor(
                apiToken,
                grazieEnvironment = GrazieEnvironment.Staging,
                authType = AuthType.User,
            )
        }
    }

    private fun llmModel(provider: KotlinLlmProvider): LLModel {
        return when (provider) {
            KotlinLlmProvider.OpenAI -> OpenAIModels.Chat.GPT5
            KotlinLlmProvider.Grazie -> CLAUDE_SONNET
        }
    }

    private class SubmittedCaseEarlyExit : RuntimeException(SUBMITTED_CASE_RESPONSE)

    private companion object {
        const val SUBMITTED_CASE_RESPONSE = "Case submitted successfully."
        val CLAUDE_SONNET = LLModel(
            provider = LLMProvider.Anthropic,
            id = "anthropic-claude-4-5-sonnet",
            capabilities = listOf(
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Vision.Image,
                LLMCapability.Document,
                LLMCapability.Completion,
            ),
            contextLength = 200_000,
            maxOutputTokens = 64_000,
        )
        val TOOLBELT_FUNCTION_NAME_REGEX =
            Regex("""\bfun\s+(?:<[^>]+>\s*)?(?:[A-Za-z_][A-Za-z0-9_<>,.? ]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    }
}
