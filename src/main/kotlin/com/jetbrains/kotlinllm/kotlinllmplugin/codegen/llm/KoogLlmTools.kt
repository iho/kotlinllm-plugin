package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.kotlinllm.kotlinllmplugin.models.render
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory

private const val DEFAULT_TOOL_PAGE_LIMIT = 2_000
private const val DEFAULT_GREP_MAX_RESULTS = 50
private const val DEFAULT_GREP_CONTEXT_CHARS = 160
private const val DEFAULT_GREP_OUTPUT_LIMIT = 1_800

@Suppress("unused")
@LLMDescription("Tools for submitting and validating parser body implementations")
class KoogParserTools(private val client: KoogLlmClient) : ToolSet {
    @Suppress("unused")
    @Tool
    @LLMDescription("Read actual values (inputs) with pagination. Use this to examine the input data. The output string is paginated by character position.")
    fun readActualValues(
        @LLMDescription("Starting character index (0-based) for pagination")
        from: Int = 0
    ): String {
        val fullOutput = buildString {
            client.fromValues.forEach { value ->
                appendLine("${value.name}: ${value.type} = ${value.value}")
            }
        }

        return paginateToolOutput(
            label = "Actual values",
            output = fullOutput,
            from = from,
            nextCall = { nextFrom -> "readActualValues(from: $nextFrom)" }
        )
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Run case-insensitive regex grep over current actual input values. Returns up to the default match limit as compact snippets with value names, line numbers, and character ranges.")
    fun grepActualInput(
        @LLMDescription("Regex search pattern.")
        pattern: String,
        @LLMDescription("Number of initial matches to skip. Use the nextCall hint to continue.")
        from: Int = 0
    ): String {
        if (pattern.isBlank()) return "Pattern is empty."

        val regexPattern = runCatching { Regex(pattern, setOf(RegexOption.IGNORE_CASE)) }
            .getOrElse { return "Invalid regex: ${it.message}" }

        val skipMatches = from.coerceAtLeast(0)
        var totalMatches = 0
        val matches = mutableListOf<GrepMatch>()

        client.fromValues.forEach { value ->
            var lineStartOffset = 0
            value.value.lineSequence().forEachIndexed { lineIndex, line ->
                regexPattern.findAll(line).forEach { match ->
                    if (totalMatches >= skipMatches && matches.size < DEFAULT_GREP_MAX_RESULTS) {
                        matches.add(
                            GrepMatch(
                                valueName = value.name,
                                valueType = value.type,
                                lineNumber = lineIndex + 1,
                                charRange = lineStartOffset + match.range.first..lineStartOffset + match.range.last,
                                snippet = line.snippetAround(match.range)
                            )
                        )
                    }
                    totalMatches++
                }
                lineStartOffset += line.length + 1
            }
        }

        if (matches.isEmpty()) {
            return if (totalMatches == 0) {
                "No matches for pattern '$pattern'."
            } else {
                "No more matches for pattern '$pattern'. Total matches: $totalMatches."
            }
        }

        return buildString {
            appendLine("grepActualInput matches starting at ${skipMatches + 1} of $totalMatches:")
            var rendered = 0
            matches.forEachIndexed { index, match ->
                val entry = buildString {
                    appendLine("${skipMatches + index + 1}: ${match.valueName}: ${match.valueType} line ${match.lineNumber}, chars ${match.charRange.first}-${match.charRange.last}")
                    appendLine(match.snippet)
                }
                if (rendered > 0 && length + entry.length > DEFAULT_GREP_OUTPUT_LIMIT) {
                    return@forEachIndexed
                }
                append(entry)
                rendered++
            }
            if (skipMatches + rendered < totalMatches) {
                appendLine("More matches available. Use grepActualInput(pattern: ${pattern.quoteForToolCall()}, from: ${skipMatches + rendered}) to continue.")
            }
        }
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Read the target type information. Use this to examine the output type that needs to be produced.")
    fun readTargetType(
        @LLMDescription("Starting character index (0-based) for pagination")
        from: Int = 0
    ): String {
        val type = client.toType ?: return "No target type available"

        val fullOutput = buildString {
            appendLine("Target Type: ${type.fullName()}")
            if (type.constructor != null) {
                appendLine()
                appendLine("Constructor:")
                appendLine(type.constructor)
            }
            appendLine()
            appendLine("Type Dependencies:")
            appendLine(type.render())
        }

        return paginateToolOutput(
            label = "Target type",
            output = fullOutput,
            from = from,
            nextCall = { nextFrom -> "readTargetType(from: $nextFrom)" }
        )
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Read the previous syntax or compilation error with pagination. Use this when the prompt says an error is present.")
    fun readPreviousError(
        @LLMDescription("Starting character index (0-based) for pagination")
        from: Int = 0
    ): String {
        val error = client.previousError
            ?: return "No previous syntax or compilation error is available."

        return paginateToolOutput(
            label = "Previous syntax or compilation error",
            output = error,
            from = from,
            nextCall = { nextFrom -> "readPreviousError(from: $nextFrom)" }
        )
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Read the previously submitted guard and case handler that failed syntax, compilation, or hot reload. Use this before retrying after an error.")
    fun readPreviousSubmittedCase(
        @LLMDescription("Starting character index (0-based) for pagination")
        from: Int = 0
    ): String {
        val submittedCase = client.previousSubmittedCase
            ?: return "No previously submitted case is available."

        val output = buildString {
            appendLine("Previous submitted guardExpression:")
            appendLine(submittedCase.guardExpression)
            appendLine()
            appendLine("Previous submitted caseHandlerBody:")
            appendLine(submittedCase.caseHandlerBody)
        }

        return paginateToolOutput(
            label = "Previous submitted case",
            output = output,
            from = from,
            nextCall = { nextFrom -> "readPreviousSubmittedCase(from: $nextFrom)" }
        )
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("List names of reusable Kotlin utility functions currently registered in the toolbelt. Use readToolbeltFunction(name) to inspect implementation only when needed.")
    fun listToolbeltFunctions(
        @LLMDescription("Starting character index (0-based) for pagination")
        from: Int = 0
    ): String {
        if (client.toolbeltFunctions.isEmpty()) {
            return "Toolbelt is empty. Register reusable helpers with registerToolbeltFunction(functionCode)."
        }

        val fullOutput = buildString {
            appendLine("Toolbelt functions (${client.toolbeltFunctions.size}):")
            client.toolbeltFunctions.keys.forEach { name ->
                appendLine("- $name")
            }
            appendLine("Use readToolbeltFunction(name) to inspect full code and examples.")
        }

        return paginateToolOutput(
            label = "Toolbelt function names",
            output = fullOutput,
            from = from,
            nextCall = { nextFrom -> "listToolbeltFunctions(from: $nextFrom)" }
        )
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Read a reusable Kotlin utility function implementation from the toolbelt by function name, with pagination. Use this before reimplementing helper logic.")
    fun readToolbeltFunction(
        @LLMDescription("Function name, for example parseJsonStringField")
        name: String,
        @LLMDescription("Starting character index (0-based) for pagination")
        from: Int = 0
    ): String {
        val functionCode = client.toolbeltFunctions[name]
            ?: return "No toolbelt function named '$name'. Use listToolbeltFunctions() to see available functions."
        return paginateToolOutput(
            label = "Toolbelt function '$name'",
            output = functionCode,
            from = from,
            nextCall = { nextFrom ->
                "readToolbeltFunction(name: ${name.quoteForToolCall()}, from: $nextFrom)"
            }
        )
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Register or replace one reusable Kotlin utility function in the toolbelt. Registration rejects helpers that fail syntax or heuristic purity checks. Add a helper only when it is useful across many possible cases, not for one-off current-case logic. The function will be inserted before generated guards/cases. Include a KDoc comment with purpose, args, return value, and an example call.")
    fun registerToolbeltFunction(
        @LLMDescription(
            "Exactly one Kotlin function. Example: /** Extracts a JSON string field. Args: json object text, field name. Returns null when missing. Example: parseJsonStringField(obj, \"title\") */ fun parseJsonStringField(json: String, fieldName: String): String? { ... }"
        )
        functionCode: String
    ): String {
        return try {
            registerToolbeltFunctionImpl(functionCode)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            "Toolbelt registration crashed before validation could finish: ${t::class.simpleName}: ${t.message ?: "no details"}"
        }
    }

    private fun registerToolbeltFunctionImpl(functionCode: String): String {
        val cleaned = functionCode.trimMarkdown().trim()
        if (cleaned.isBlank()) return "Function code is empty."
        val project = client.projectRef ?: return "No project context is available."

        val validation = validateToolbeltFunction(project, cleaned)
        if (validation is ToolbeltFunctionValidation.Invalid) {
            return buildString {
                appendLine("Invalid toolbelt function.")
                appendLine(validation.message)
                appendLine()
                appendLine("Submit exactly one local-compatible Kotlin function.")
                appendLine("Required shape:")
                appendLine("/** Purpose. Args: ... Returns: ... Example: helper(input) */")
                appendLine("fun helper(input: String): String? {")
                appendLine("    return null")
                appendLine("}")
            }.trimEnd()
        }
        validation as ToolbeltFunctionValidation.Valid
        val purityIssues = findPurityIssues(validation.functionCode.asToolbeltPurityRequest())
        if (purityIssues.isNotEmpty()) {
            return buildString {
                appendLine("Invalid toolbelt function.")
                appendLine("Unsafe code rejected by heuristic purity check.")
                appendLine(purityIssues.first())
                purityIssues.drop(1).take(5).forEach { issue ->
                    appendLine("- $issue")
                }
            }.trimEnd()
        }

        val replaced = client.toolbeltFunctions.containsKey(validation.name)
        client.toolbeltFunctions[validation.name] = validation.functionCode
        return buildString {
            if (replaced) {
                appendLine("Toolbelt function '${validation.name}' replaced.")
            } else {
                appendLine("Toolbelt function '${validation.name}' registered.")
            }
            if (validation.warnings.isNotEmpty()) {
                appendLine("Warnings:")
                validation.warnings.forEach { warning -> appendLine("- $warning") }
            }
        }.trimEnd()
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Remove a utility function from the toolbelt by name. Use only when a helper is obsolete or wrong.")
    fun removeToolbeltFunction(
        @LLMDescription("Function name to remove")
        name: String
    ): String {
        val removed = client.toolbeltFunctions.remove(name)
        return if (removed == null) {
            "No toolbelt function named '$name'."
        } else {
            "Toolbelt function '$name' removed."
        }
    }

    @Suppress("unused")
    @Tool
    @LLMDescription("Submit one complete generated case atomically: the guard expression and the matching handler body. Runs syntax and heuristic purity checks before accepting the case.")
    fun submitCase(
        @LLMDescription("Kotlin boolean expression used in `if (<guard>)`")
        guardExpression: String,
        @LLMDescription("Kotlin statements for the matching case handler body. Return ParsedResult(...), or null when assumptions fail.")
        caseHandlerBody: String
    ): String {
        return try {
            submitCaseImpl(guardExpression, caseHandlerBody)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            val cause = t.rootCause()
            val message = "Submitting failed: submitCase crashed: ${cause::class.simpleName}: ${cause.message ?: "no details"}"
            runCatching { client.reportStatus("Submitting failed.") }
            message
        }
    }

    private fun submitCaseImpl(
        guardExpression: String,
        caseHandlerBody: String
    ): String {
        val cleaned = guardExpression.trimMarkdown().trim()
        if (cleaned.isBlank()) {
            client.reportStatus("Submitting failed.")
            return "Guard expression is empty."
        }
        val cleanedHandler = caseHandlerBody.trimMarkdown().trim()
        if (cleanedHandler.isBlank()) {
            client.reportStatus("Submitting failed.")
            return "Case handler body is empty."
        }

        val project = client.projectRef ?: run {
            client.reportStatus("Submitting failed.")
            return "No project context is available."
        }
        val guardExpressionForSyntax = cleaned.withoutLeadingGuardDocComment()
        val guardSyntaxError = try {
            runReadAction {
                val psiFactory = KtPsiFactory(project)
                psiFactory.createExpression(guardExpressionForSyntax)
            }
            null
        } catch (e: Exception) {
            "Syntax error: ${e.message}"
        }

        val handlerSyntaxError = try {
            runReadAction {
                val psiFactory = KtPsiFactory(project)
                psiFactory.createFunction(
                    """
                    fun __kotlinLlmTmpHandler(): Any? {
                        $cleanedHandler
                    }
                    """.trimIndent()
                )
            }
            null
        } catch (e: Exception) {
            "Syntax error: ${e.message}"
        }
        val purityIssues = findSubmissionPurityIssues(cleanedHandler)

        return buildString {
            if (guardSyntaxError != null) {
                appendLine("Invalid guard expression.")
                appendLine(guardSyntaxError)
                appendLine()
            }
            if (handlerSyntaxError != null) {
                appendLine("Invalid case handler body.")
                appendLine(handlerSyntaxError)
                appendLine()
            }
            if (purityIssues.isNotEmpty()) {
                appendLine("Unsafe case rejected by heuristic purity check.")
                appendLine(purityIssues.first())
                purityIssues.drop(1).take(5).forEach { issue ->
                    appendLine("- $issue")
                }
                appendLine()
            }
            if (guardSyntaxError != null || handlerSyntaxError != null || purityIssues.isNotEmpty()) {
                client.reportStatus("Submitting failed.")
                append("Please fix both parts and resubmit the complete case with submitCase.")
            } else {
                client.submittedCase = KoogLlmClient.AsLlmCaseUpdate(cleaned, cleanedHandler)
                client.reportStatus("Submitting accepted.")
                append("Case submitted successfully.")
            }
        }
    }

    private fun Throwable.rootCause(): Throwable {
        var current = this
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private sealed class ToolbeltFunctionValidation {
        data class Valid(
            val name: String,
            val functionCode: String,
            val warnings: List<String>,
        ) : ToolbeltFunctionValidation()

        data class Invalid(val message: String) : ToolbeltFunctionValidation()
    }

    private data class NormalizedToolbeltFunction(
        val code: String,
        val warnings: List<String>,
    )

    private data class GrepMatch(
        val valueName: String,
        val valueType: String,
        val lineNumber: Int,
        val charRange: IntRange,
        val snippet: String,
    )

    private data class UnsafePattern(
        val regex: Regex,
        val message: String,
    )

    private val unsafePurityPatterns = listOf(
        UnsafePattern(
            Regex("""\b(import|package|class|object|interface|enum|annotation)\b|@[A-Za-z_][A-Za-z0-9_]*|\breturn@"""),
            "Generated handler code must not declare packages, imports, types, annotations, or labeled returns"
        ),
        UnsafePattern(
            Regex("""\b(java\.io|java\.nio|kotlin\.io|File|Files|Path|Paths|readText|writeText|appendText|copyTo|delete|mkdirs?|inputStream|outputStream|bufferedReader|reader|writer|useLines)\b"""),
            "File or filesystem access is not allowed"
        ),
        UnsafePattern(
            Regex("""\b(java\.net|URL|URI|URLConnection|HttpClient|Socket|ServerSocket|DatagramSocket|InetAddress|OkHttpClient|ktor|retrofit)\b"""),
            "Network access is not allowed"
        ),
        UnsafePattern(
            Regex("""\b(ProcessBuilder|Runtime\s*\.\s*getRuntime|exitProcess)\b|\.exec\s*\(|\bSystem\s*\.\s*exit\s*\("""),
            "Process execution or JVM exit is not allowed"
        ),
        UnsafePattern(
            Regex("""\bSystem\s*\.\s*(getenv|getProperty|setProperty|clearProperty|setOut|setErr)\s*\(|\bSystem\s*\.\s*(out|err)\b|\b(?:println|print|readLine)\s*\("""),
            "Console, environment, or system property access is not allowed"
        ),
        UnsafePattern(
            Regex("""::class\b|\.javaClass\b|\bClass\s*\.\s*forName\s*\(|\.classLoader\b|\b(kotlin\.reflect|java\.lang\.reflect)\b|\bgetDeclared[A-Za-z0-9_]*\s*\(|\bgetMethod\s*\("""),
            "Reflection or classloader access is not allowed"
        ),
        UnsafePattern(
            Regex("""\b(Thread|Runnable|synchronized|kotlinx\.coroutines)\b|\b(wait|notify|notifyAll|launch|async|runBlocking|delay|sleep)\s*\("""),
            "Threading, synchronization, or coroutine operations are not allowed"
        ),
        UnsafePattern(
            Regex("""\bSystem\s*\.\s*(currentTimeMillis|nanoTime)\s*\(|\b(java\.time|kotlin\.time)\b|\b(Date|Instant|Clock|Random|UUID)\s*(?:\.|\()"""),
            "Clock, time, random, or UUID usage is not allowed"
        ),
        UnsafePattern(
            Regex("""\b(ApplicationManager|ProjectManager|ServiceManager|Psi[A-Za-z0-9_]*|runReadAction|runWriteAction)\b|\bservice\s*<"""),
            "IDE services, project APIs, and PSI access are not allowed"
        ),
    )

    private fun findSubmissionPurityIssues(caseHandlerBody: String): List<String> {
        val request = buildString {
            appendLine("mode: ${client.statsMode}")
            appendLine("caseHandlerBody:")
            appendLine("```kotlin")
            appendLine(caseHandlerBody)
            appendLine("```")
        }

        return buildList {
            addAll(findPurityIssues(request))
            client.toolbeltFunctions.forEach { (name, functionCode) ->
                findPurityIssues(functionCode.asToolbeltPurityRequest())
                    .forEach { issue -> add("Registered toolbelt function '$name' is unsafe: $issue") }
            }
        }.distinct()
    }

    private fun findPurityIssues(request: String): List<String> {
        val code = request.extractReviewCode()
        val searchableCode = code.stripKotlinCommentsAndStrings()
        val mockMode = Regex("""\bmockLlm\b""", RegexOption.IGNORE_CASE).containsMatchIn(request)
        val localNames = Regex("""\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .findAll(searchableCode)
            .map { it.groupValues[1] }
            .toSet()

        val issues = mutableListOf<String>()
        unsafePurityPatterns.forEach { pattern ->
            pattern.regex.find(searchableCode)?.let { match ->
                issues.add("${pattern.message}: `${match.value.trim()}`")
            }
        }
        issues.addAll(findExternalMutationIssues(searchableCode, localNames, mockMode))

        return issues.distinct()
    }

    private fun findExternalMutationIssues(
        code: String,
        localNames: Set<String>,
        mockMode: Boolean,
    ): List<String> {
        val issues = mutableListOf<String>()
        val mutationAssignments = Regex(
            """\b([A-Za-z_][A-Za-z0-9_]*)\s*(?:(?:\[[^\]\n]*\])|(?:\.[A-Za-z_][A-Za-z0-9_]*))\s*(?:[+\-*/%]?=|\+\+|--)"""
        )
        mutationAssignments.findAll(code).forEach { match ->
            val receiver = match.groupValues[1]
            if (!isAllowedMutationReceiver(receiver, localNames, mockMode)) {
                issues.add("Mutation appears to target non-local state or an input object: `${match.value.trim()}`")
            }
        }

        val mutatingCalls = Regex(
            """\b([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*(add|addAll|append|clear|put|putAll|remove|removeAll|replace|set|sort|shuffle|reverse)\s*\("""
        )
        mutatingCalls.findAll(code).forEach { match ->
            val receiver = match.groupValues[1]
            if (!isAllowedMutationReceiver(receiver, localNames, mockMode)) {
                issues.add("Mutating call appears to target non-local state or an input object: `${match.value.trim()}`")
            }
        }

        return issues
    }

    private fun isAllowedMutationReceiver(
        receiver: String,
        localNames: Set<String>,
        mockMode: Boolean,
    ): Boolean {
        return receiver in localNames || (mockMode && receiver == "state")
    }

    private fun String.extractReviewCode(): String {
        val fencedBlock = Regex("""(?s)```(?:kotlin|kt)?\s*(.*?)```""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!fencedBlock.isNullOrBlank()) return fencedBlock

        val markerRegex = Regex(
            """(?is)(?:caseHandlerBody|functionCode|generated Kotlin code|code)\s*:?\s*(.*)"""
        )
        return markerRegex.find(this)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: this.trim()
    }

    private fun String.asToolbeltPurityRequest(): String {
        return buildString {
            appendLine("mode: toolbeltHelper")
            appendLine("functionCode:")
            appendLine("```kotlin")
            appendLine(this@asToolbeltPurityRequest)
            appendLine("```")
        }
    }

    private fun String.withoutLeadingGuardDocComment(): String {
        val trimmed = trim()
        val match = Regex("""(?s)^/\*\*.*?\*/\s*(.+)$""").matchEntire(trimmed)
        return match?.groupValues?.getOrNull(1)?.trim() ?: trimmed
    }

    private fun String.snippetAround(matchRange: IntRange): String {
        if (isEmpty()) return ""
        val start = (matchRange.first - DEFAULT_GREP_CONTEXT_CHARS).coerceAtLeast(0)
        val endInclusive = (matchRange.last + DEFAULT_GREP_CONTEXT_CHARS).coerceIn(0, lastIndex)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (endInclusive < lastIndex) "..." else ""
        return prefix + substring(start, endInclusive + 1) + suffix
    }

    private fun String.stripKotlinCommentsAndStrings(): String {
        val result = StringBuilder(length)
        var index = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inTripleString = false
        var inChar = false
        var escaped = false

        while (index < length) {
            val current = this[index]
            val next = getOrNull(index + 1)
            val nextTwo = if (index + 2 < length) substring(index, index + 3) else null

            when {
                inLineComment -> {
                    if (current == '\n') {
                        inLineComment = false
                        result.append(current)
                    } else {
                        result.append(' ')
                    }
                    index++
                }

                inBlockComment -> {
                    if (current == '*' && next == '/') {
                        result.append("  ")
                        index += 2
                        inBlockComment = false
                    } else {
                        result.append(if (current == '\n') '\n' else ' ')
                        index++
                    }
                }

                inTripleString -> {
                    if (nextTwo == "\"\"\"") {
                        result.append("   ")
                        index += 3
                        inTripleString = false
                    } else {
                        result.append(if (current == '\n') '\n' else ' ')
                        index++
                    }
                }

                inString -> {
                    result.append(if (current == '\n') '\n' else ' ')
                    if (!escaped && current == '"') {
                        inString = false
                    }
                    escaped = !escaped && current == '\\'
                    if (current != '\\') {
                        escaped = false
                    }
                    index++
                }

                inChar -> {
                    result.append(if (current == '\n') '\n' else ' ')
                    if (!escaped && current == '\'') {
                        inChar = false
                    }
                    escaped = !escaped && current == '\\'
                    if (current != '\\') {
                        escaped = false
                    }
                    index++
                }

                current == '/' && next == '/' -> {
                    result.append("  ")
                    index += 2
                    inLineComment = true
                }

                current == '/' && next == '*' -> {
                    result.append("  ")
                    index += 2
                    inBlockComment = true
                }

                nextTwo == "\"\"\"" -> {
                    result.append("   ")
                    index += 3
                    inTripleString = true
                }

                current == '"' -> {
                    result.append(' ')
                    index++
                    inString = true
                    escaped = false
                }

                current == '\'' -> {
                    result.append(' ')
                    index++
                    inChar = true
                    escaped = false
                }

                else -> {
                    result.append(current)
                    index++
                }
            }
        }

        return result.toString()
    }

    private fun validateToolbeltFunction(
        project: com.intellij.openapi.project.Project,
        functionCode: String
    ): ToolbeltFunctionValidation {
        return try {
            runReadAction {
                val psiFactory = KtPsiFactory(project)
                val file = psiFactory.createFile(functionCode)

                val packageName = file.packageFqName.asString()
                    .takeUnless { it.isBlank() || it == "<root>" }
                if (packageName != null) {
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "Do not include package directive '$packageName'. Toolbelt helpers are inserted inside the generated lambda."
                    )
                }
                if (file.importDirectives.isNotEmpty()) {
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "Do not include imports in toolbelt helpers. Use Kotlin stdlib types directly or fully qualify non-imported types."
                    )
                }

                val functions = file
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                if (functions.isEmpty()) {
                    firstCodePsiError(file)?.let { error ->
                        return@runReadAction ToolbeltFunctionValidation.Invalid(
                            "Kotlin parse error: ${error.toDiagnosticString()}"
                        )
                    }
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "No Kotlin function declaration was found. Do not include prose; pass the function code only."
                    )
                }
                if (functions.size != 1) {
                    val names = functions.joinToString(", ") { it.name ?: "<anonymous>" }
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "Expected exactly one function, but found ${functions.size}: $names. Register helpers one at a time."
                    )
                }

                val otherDeclarations = file.declarations.filterNot { it is KtNamedFunction }
                if (otherDeclarations.isNotEmpty()) {
                    val declarationKinds = otherDeclarations.joinToString(", ") { it::class.simpleName ?: "declaration" }
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "Only a function declaration is allowed. Remove extra declarations: $declarationKinds."
                    )
                }

                val function = functions.single()
                firstCodePsiError(function)?.let { error ->
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "Function syntax error: ${error.toDiagnosticString()}"
                    )
                }

                val name = function.name
                    ?: return@runReadAction ToolbeltFunctionValidation.Invalid("Function name is missing.")
                if (name.startsWith("__kotlinLlm")) {
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "Function name '$name' is reserved. Use a name that does not start with __kotlinLlm."
                    )
                }

                if (function.typeReference == null) {
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "Function '$name' must declare an explicit return type, for example `fun $name(...): String?`."
                    )
                }

                val missingParameterTypes = function.valueParameters
                    .mapIndexedNotNull { index, parameter ->
                        if (parameter.typeReference == null) parameter.name ?: "parameter #${index + 1}" else null
                    }
                if (missingParameterTypes.isNotEmpty()) {
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "Function '$name' has parameter(s) without explicit types: ${missingParameterTypes.joinToString(", ")}."
                    )
                }

                val normalized = normalizeToolbeltFunctionText(functionCode)
                val normalizedFile = psiFactory.createFile(normalized.code)
                firstCodePsiError(normalizedFile)?.let { error ->
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "Function '$name' has a syntax error after normalization: ${error.toDiagnosticString()}"
                    )
                }
                if (normalizedFile
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .singleOrNull()
                    == null
                ) {
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "Function '$name' could not be parsed after normalization."
                    )
                }

                val localValidationFunction = psiFactory.createFunction(
                    """
                    fun __kotlinLlmValidateToolbeltFunctionScope(): Any? {
                        ${normalized.code}
                        return null
                    }
                    """.trimIndent()
                )
                firstCodePsiError(localValidationFunction)?.let { error ->
                    return@runReadAction ToolbeltFunctionValidation.Invalid(
                        "Function '$name' is not compatible with insertion into the generated lambda: ${error.toDiagnosticString()}"
                    )
                }

                val warnings = buildList {
                    addAll(normalized.warnings)
                    if (!hasKDoc(normalized.code)) {
                        add(
                            "Missing KDoc. The function was registered anyway, but add `/** ... */` with purpose, args, return value, and an example call so future generations can reuse it safely."
                        )
                    }
                }

                ToolbeltFunctionValidation.Valid(
                    name = name,
                    functionCode = normalized.code.trim(),
                    warnings = warnings,
                )
            }
        } catch (e: Exception) {
            ToolbeltFunctionValidation.Invalid("Kotlin parser threw ${e::class.simpleName}: ${e.message ?: "no details"}")
        }
    }

    private fun normalizeToolbeltFunctionText(functionText: String): NormalizedToolbeltFunction {
        val warnings = mutableListOf<String>()
        var normalized = functionText.trim()
        val visibilityRegex =
            Regex("""(?m)^(\s*)(public|private|internal|protected)\s+((?:(?:tailrec|operator|infix|inline|suspend)\s+)*fun\b)""")
        var removedVisibility = false
        normalized = visibilityRegex.replace(normalized) { match ->
            removedVisibility = true
            "${match.groupValues[1]}${match.groupValues[3]}"
        }
        if (removedVisibility) {
            warnings.add("Removed top-level visibility modifier; toolbelt helpers are inserted as local functions.")
        }

        return NormalizedToolbeltFunction(normalized, warnings)
    }

    private fun hasKDoc(functionCode: String): Boolean {
        val trimmed = functionCode.trimStart()
        return trimmed.startsWith("/**") && trimmed.indexOf("*/") >= 0
    }

    private fun firstCodePsiError(element: PsiElement): PsiErrorElement? {
        return PsiTreeUtil.findChildrenOfType(element, PsiErrorElement::class.java)
            .firstOrNull { !it.isInsideComment() }
    }

    private fun PsiElement.isInsideComment(): Boolean {
        var current: PsiElement? = this
        while (current != null) {
            if (current is PsiComment || current.javaClass.simpleName.contains("KDoc")) return true
            current = current.parent
        }
        return false
    }

    private fun PsiErrorElement.toDiagnosticString(): String {
        val near = text
            .replace("\n", "\\n")
            .take(120)
            .takeIf { it.isNotBlank() }
        return if (near == null) {
            errorDescription
        } else {
            "$errorDescription near `$near`"
        }
    }

}

private fun paginateToolOutput(
    label: String,
    output: String,
    from: Int,
    nextCall: ((nextFrom: Int) -> String)?
): String {
    val validFrom = from.coerceAtLeast(0)
    val validLimit = DEFAULT_TOOL_PAGE_LIMIT
    val totalLength = output.length

    if (validFrom >= totalLength) {
        return "No more data for $label. Total length: $totalLength characters."
    }

    val endIndex = (validFrom + validLimit).coerceAtMost(totalLength)
    return buildString {
        appendLine("$label (characters $validFrom to ${endIndex - 1} of $totalLength total):")
        appendLine()
        append(output.substring(validFrom, endIndex))
        if (endIndex < totalLength) {
            appendLine()
            appendLine("---")
            if (nextCall != null) {
                appendLine("More data available. Use ${nextCall(endIndex)} to continue.")
            } else {
                appendLine("More data available, but this tool has a fixed output window. Use a narrower query if needed.")
            }
        }
    }
}

private fun String.quoteForToolCall(): String {
    return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
