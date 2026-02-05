package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm

interface LlmClient {
    suspend fun chat(system: String, user: String): String
}

internal fun String.trimMarkdown(): String {
    val trimmed = trim()
    val lines = trimmed.lines()
    val firstLine = lines.firstOrNull()?.trimStart()
    if (firstLine?.startsWith("```") != true) return this

    val bodyLines = lines.drop(1)
    val withoutClosingFence = if (bodyLines.lastOrNull()?.trim()?.startsWith("```") == true) {
        bodyLines.dropLast(1)
    } else {
        bodyLines
    }
    return withoutClosingFence.joinToString("\n")
}
