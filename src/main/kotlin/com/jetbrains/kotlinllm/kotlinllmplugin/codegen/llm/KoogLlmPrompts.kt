package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm

import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualType
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualValue
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedInterface
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedMethod

private const val HINT_PARAMETER_NAME = "hint"

suspend fun KoogLlmClient.asLlmLogic(
    fromValues: List<ActualValue>,
    toType: ActualType,
    hint: String?,
    previousSubmittedCase: KoogLlmClient.AsLlmCaseUpdate? = null,
    existingToolbeltFunctions: List<String> = emptyList(),
    errorMessage: String? = null,
): KoogLlmClient.AsLlmCaseUpdate {
    this.fromValues = fromValues
        .filterNot { it.name == HINT_PARAMETER_NAME }
    this.toType = toType
    this.statsMode = "asLlm"
    this.submittedCase = null
    this.previousError = errorMessage
    this.previousSubmittedCase = previousSubmittedCase
    setToolbeltFunctions(existingToolbeltFunctions)

    val systemPrompt = """
        Generate one Kotlin `asLlm<F, T>(from)` case for the current observed scenario only.
        Submit only via submitCase(guardExpression, caseHandlerBody); do not output a lambda.
        The handler must return ParsedResult(...), or null when assumptions fail. ParsedResult is local; use it unqualified.

        Guard rules:
        - The guard is the scenario classifier. It must reject unknown or materially different future inputs.
        - Guard on facts the handler depends on: shape, tags/statuses, required fields, counts, relationships, null/blank/missing values, ranges, enum-like strings, and format markers.
        - Prefer a narrow guard over a broad one; do not use only non-null/non-empty/basic-shape checks unless that fully identifies the scenario.
        - If a user hint exists, follow it when forming the guard and verify before submission that the guard follows the hint. Never reference `$HINT_PARAMETER_NAME` in generated code.
        - If a user hint exists, the guardExpression must include a docstring-style Kotlin comment explaining how the hint relates to the guard; the explanation must directly match the guard logic.

        Use pure Kotlin only: no imports, classes, fields, external libraries, side effects, or labeled returns.
        Check the toolbelt before writing reusable helper logic; register a helper only if it is useful across many possible cases.
        submitCase and helper registration run syntax and heuristic purity checks. If rejected, fix and resubmit the complete case.
        Tool calls are limited; inspect only what you need, then submit.
    """.trimIndent()

    val userPrompt = buildString {
        if (errorMessage == null) {
            appendLine("Create ONE new asLlm case for current scenario.")
            appendLine("Available tools: readActualValues, grepActualInput, readTargetType, listToolbeltFunctions, readToolbeltFunction, registerToolbeltFunction, removeToolbeltFunction, submitCase.")
            if (!hint.isNullOrBlank()) {
                appendLine()
                appendLine("Guard hint:")
                appendLine("Hint: $hint")
                appendLine("Include a docstring-style Kotlin comment in guardExpression explaining how this hint maps to the guard.")
            }
            appendLine()
            appendLine("Finish by calling submitCase(guardExpression = <boolean expression>, caseHandlerBody = <statements-only body>).")
        } else {
            appendLine("The previous asLlm submission failed. Fix the submission using only the previous submission and compilation error below.")
            appendLine()
            appendPreviousSubmission(previousSubmittedCase)
            appendLine()
            appendLine("Compilation error:")
            appendLine(errorMessage)
            appendLine()
            appendLine("Resubmit the complete corrected case with submitCase.")
        }
    }

    chat(systemPrompt, userPrompt)
    return submittedCase
        ?: throw IllegalStateException("Model did not submit case via submitCase")
}

suspend fun KoogLlmClient.mockLlmLogic(
    actualValues: List<ActualValue>,
    toType: ActualType,
    mockedInterface: MockedInterface?,
    currentMethod: MockedMethod?,
    previousSubmittedCase: KoogLlmClient.AsLlmCaseUpdate? = null,
    existingToolbeltFunctions: List<String> = emptyList(),
    errorMessage: String? = null,
): KoogLlmClient.AsLlmCaseUpdate {
    this.fromValues = actualValues
    this.toType = toType
    this.statsMode = "mockLlm"
    this.submittedCase = null
    this.previousError = errorMessage
    this.previousSubmittedCase = previousSubmittedCase
    setToolbeltFunctions(existingToolbeltFunctions)

    val systemPrompt = """
        You generate Kotlin Smart Macro code for `mockLlm<T>()`.
        Add exactly one observed call scenario, preserve previous behavior, and return null for unknown/logically different calls.
        Implement the actual behavior of the mocked method for the observed scenario.
        Infer the method intent from the interface, method name, return type, current arguments, and existing toolbelt functions.
        Do not submit placeholder logic, unconditional canned defaults, echo-only responses, or trivial null/empty/zero/false results
        unless that is the correct behavior for this method and observed call.
        The case handler must compute the returned value from the observed inputs and method semantics whenever the output is derivable.
        A guard is a scenario classifier, not a cheap sanity check.
        Guard on semantic discriminators: argument values, counts, ordering, relationships between fields,
        sentinel values, missing/null/blank values, range boundaries, enum-like strings, and format markers.
        Generated code has access to mutable `state: MutableMap<Any?, Any?>` and `stateSnapshot: String`.
        Observed call values are string snapshots for readability; generated code receives the real typed method arguments.
        Use `stateSnapshot` to understand the current mock state contents, and mutate `state` when the method behavior should remember or update information.
        If the handler relies on a fact, the guard must establish that fact or the handler must safely decline by returning null.
        Use only observable current call facts; do not infer future behavior or solve the whole domain.
        Use pure Kotlin only: no external libraries/frameworks, imports, classes, fields, or labeled returns.
        Prefer simple imperative control flow. Treat Kotlin scalars as terminal values.
        Output is not a full lambda. Submit the guard and handler together with one tool call:
        - submitCase(guardExpression = <boolean expression>, caseHandlerBody = <statements-only body>)
        Return the mocked method value wrapped in ParsedResult(...), else return null.
        Do not add state or stateSnapshot to the returned value. ParsedResult is in the same generated scope; use it unqualified.
        The runtime composes branching and fallback automatically.
        Check the toolbelt before writing helper logic. Register reusable helpers with KDoc; keep one-off logic in the case handler.
        Toolbelt helper registration and submitCase both run automatic syntax and heuristic purity checks.
        If submitCase rejects code as unsafe, fix the issue and resubmit the complete case.
        Generated code is applied via class reload, so stay reload-safe.
        Tool outputs use fixed page sizes; use targeted grepActualInput searches and continue read tools with from only when needed.
    """.trimIndent()

    val userPrompt = buildString {
        if (errorMessage == null) {
            appendLine("Create ONE new mockLlm case for method `${currentMethod?.name ?: "unknown"}` of interface `${mockedInterface?.name ?: "unknown"}`.")
            appendLine(
                "Use tools: readActualValues, grepActualInput, readTargetType, listToolbeltFunctions, readToolbeltFunction, registerToolbeltFunction, removeToolbeltFunction, submitCase"
            )
            appendLine("Before writing the handler, inspect the target type and implement the method's actual logic for this observed call.")
            appendLine("Current observed call:")
            actualValues.forEach { appendLine("- ${it.name}: ${it.type} = ${it.value}") }
            appendLine("Return type: ${toType.fullName(fullyQualified = true)}")
            appendLine("`stateSnapshot` is a string view of the current mock state. Generated code can mutate the `state` map when this method should remember or update information.")
            appendLine("Observed call argument values are string snapshots for readability; generated code receives real typed method arguments.")
            appendLine("Before submitting, inspect the current arguments and identify the current scenario category and corner-case status.")
            appendLine("Design the guard as a classifier for that scenario. Guard on every input value that logically influences the output.")
            appendLine("Do not return a generic placeholder/default. Return the value this method should produce for these inputs.")
            appendLine("If you cannot prove a handler assumption in the guard, make the handler return null for that assumption instead of throwing.")
            appendLine()
            appendLine("Required finish sequence:")
            appendLine("1) call submitCase(guardExpression = <boolean expression>, caseHandlerBody = <statements-only body>)")
        } else {
            appendLine("The previous mockLlm submission failed. Fix the submission using only the previous submission and compilation error below.")
            appendLine()
            appendPreviousSubmission(previousSubmittedCase)
            appendLine()
            appendLine("Compilation error:")
            appendLine(errorMessage)
            appendLine()
            appendLine("Resubmit the complete corrected case with submitCase.")
        }
    }

    chat(systemPrompt, userPrompt)
    return submittedCase
        ?: throw IllegalStateException("Model did not submit case via submitCase")
}

private fun StringBuilder.appendPreviousSubmission(previousSubmittedCase: KoogLlmClient.AsLlmCaseUpdate?) {
    if (previousSubmittedCase == null) {
        appendLine("Previous submission: unavailable.")
        return
    }

    appendLine("Previous guardExpression:")
    appendLine(previousSubmittedCase.guardExpression)
    appendLine()
    appendLine("Previous caseHandlerBody:")
    appendLine(previousSubmittedCase.caseHandlerBody)
}
