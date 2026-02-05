package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template

import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

internal const val AS_LLM_TOOLBELT_START = "// KOTLIN_LLM_TOOLBELT_START"
internal const val AS_LLM_TOOLBELT_END = "// KOTLIN_LLM_TOOLBELT_END"
internal const val AS_LLM_CASES_START = "// KOTLIN_LLM_CASES_START"
internal const val AS_LLM_CASES_END = "// KOTLIN_LLM_CASES_END"

internal data class AsLlmGeneratedCase(
    val index: Int,
    val guardExpression: String,
    val caseHandlerBody: String,
)

internal fun renderAsLlmToolbeltBlock(toolbeltFunctions: List<String>): String {
    return buildString {
        appendLine(AS_LLM_TOOLBELT_START)
        if (toolbeltFunctions.isEmpty()) {
            appendLine("// No reusable utility functions registered yet.")
        } else {
            toolbeltFunctions.forEach { functionText ->
                appendLine(functionText.trim())
                appendLine()
            }
        }
        appendLine(AS_LLM_TOOLBELT_END)
    }.trimEnd()
}

internal fun renderAsLlmCasesBlock(
    cases: List<AsLlmGeneratedCase>,
    functionParamsText: String,
): String {
    return buildString {
        appendLine(AS_LLM_CASES_START)
        cases.forEach { case ->
            case.guardExpression.leadingGuardDocComment()?.let { comment ->
                appendLine(comment)
            }
            appendLine("fun __kotlinLlmGuard${case.index}($functionParamsText): Boolean = (${case.guardExpression.withoutLeadingGuardDocComment()})")
            appendLine("fun __kotlinLlmCase${case.index}($functionParamsText): ParsedResult? {")
            appendLine(case.caseHandlerBody)
            appendLine("}")
        }
        appendLine(AS_LLM_CASES_END)
    }.trimEnd()
}

internal fun renderAsLlmCaseChain(
    cases: List<AsLlmGeneratedCase>,
    functionParamsText: String,
    invocationArgs: String,
    returnOnComplete: Boolean = true,
): String {
    return buildString {
        appendLine("var __kotlinLlmResult: ParsedResult? = null")
        cases.asReversed().forEach { case ->
            appendLine("if (__kotlinLlmResult == null && runCatching { __kotlinLlmGuard${case.index}($invocationArgs) }.getOrDefault(false)) {")
            appendLine("    __kotlinLlmResult = runCatching { __kotlinLlmCase${case.index}($invocationArgs) }.getOrNull()")
            appendLine("}")
        }
        if (returnOnComplete) {
            appendLine("return __kotlinLlmResult")
        }
    }.trimEnd()
}

internal fun extractAsLlmGeneratedCases(statements: List<KtExpression>): List<AsLlmGeneratedCase> {
    val guards = statements
        .filterIsInstance<KtNamedFunction>()
        .mapNotNull { function ->
            val index = function.name?.removePrefix("__kotlinLlmGuard")?.toIntOrNull()
                ?: return@mapNotNull null
            val guardExpression = function.bodyExpression?.text?.trim()
                ?: return@mapNotNull null
            index to guardExpression
        }
        .toMap()
    val cases = statements
        .filterIsInstance<KtNamedFunction>()
        .mapNotNull { function ->
            val index = function.name?.removePrefix("__kotlinLlmCase")?.toIntOrNull()
                ?: return@mapNotNull null
            val body = (function.bodyExpression as? KtBlockExpression)
                ?.statements
                ?.joinToString("\n") { it.text }
                ?.trim()
                ?: return@mapNotNull null
            index to body
        }
        .toMap()

    return guards.keys
        .intersect(cases.keys)
        .sorted()
        .map { index ->
            AsLlmGeneratedCase(
                index = index,
                guardExpression = guards.getValue(index),
                caseHandlerBody = cases.getValue(index),
            )
    }
}

internal fun String.withoutLeadingGuardDocComment(): String {
    val trimmed = trim()
    val match = LEADING_GUARD_DOC_REGEX.matchEntire(trimmed)
    return match?.groupValues?.getOrNull(2)?.trim() ?: trimmed
}

private fun String.leadingGuardDocComment(): String? {
    val trimmed = trim()
    return LEADING_GUARD_DOC_REGEX.matchEntire(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
}

private val LEADING_GUARD_DOC_REGEX = Regex("""(?s)^(/\*\*.*?\*/)\s*(.+)$""")
