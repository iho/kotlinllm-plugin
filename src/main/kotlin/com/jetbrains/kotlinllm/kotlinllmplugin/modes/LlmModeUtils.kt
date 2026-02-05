package com.jetbrains.kotlinllm.kotlinllmplugin.modes

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.KoogLlmClient
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.AS_LLM_TOOLBELT_END
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.AS_LLM_TOOLBELT_START
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.AsLlmGeneratedCase
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.extractAsLlmGeneratedCases
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.renderAsLlmCaseChain
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.renderAsLlmCasesBlock
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.renderAsLlmToolbeltBlock
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.findGeneratedSourceFile
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.withoutLeadingGuardDocComment
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.psi.*

/**
 * Shared utility functions for LLM modes.
 */
object LlmModeUtils {
    sealed class UpdateResult {
        data object Success : UpdateResult()
        data class SyntaxError(val errorMessage: String) : UpdateResult()
        data class GenerationError(val errorMessage: String) : UpdateResult()
    }

    suspend fun updateMethodImplementation(
        project: Project,
        trackedMethod: TrackedMethod,
        maxRetries: Int = 1,
        generateNewBody: suspend (oldBodyText: String?, errorMessage: String?) -> String
    ): UpdateResult {
        val sourcePath = "${trackedMethod.implementationClassName}.kt"

        val (implementationElement, oldBodyText) = runReadAction {
            val configuredVf = findGeneratedSourceFile(project, sourcePath, trackedMethod.implementationPackageName)
                ?: return@runReadAction null
            val ktFile = configuredVf.toPsiFile(project) as? KtFile ?: return@runReadAction null

            val targetClass = PsiTreeUtil.findChildrenOfType(ktFile, KtClass::class.java)
                .firstOrNull { it.name == trackedMethod.implementationClassName }
                ?: return@runReadAction null

            val implementationName = "${trackedMethod.mockedMethod.name}_"
            val function = PsiTreeUtil.findChildrenOfType(targetClass, KtNamedFunction::class.java)
                .firstOrNull { it.name == implementationName }
            if (function != null) {
                return@runReadAction Pair(function as PsiElement, implementationFunctionToLambdaText(function))
            }

            val property = PsiTreeUtil.findChildrenOfType(targetClass, KtProperty::class.java)
                .firstOrNull { it.name == implementationName }
                ?: return@runReadAction null

            val oldBodyExpression = findGeneratedImplementationLambda(property)
                ?: return@runReadAction null

            Pair(property as PsiElement, oldBodyExpression.text)
        } ?: return UpdateResult.GenerationError("Could not find method implementation in source")

        var attempt = 0
        var errorMessage: String? = null

        while (attempt <= maxRetries) {
            attempt++

            val generatedBodyText = try {
                generateNewBody(oldBodyText, errorMessage)
            } catch (e: Exception) {
                return UpdateResult.GenerationError("LLM generation failed: ${e.message}")
            }

            when (val syntaxResult = tryUpdateMethodBody(project, implementationElement, generatedBodyText)) {
                is UpdateResult.Success -> return UpdateResult.Success
                is UpdateResult.SyntaxError -> {
                    if (attempt > maxRetries) {
                        tryUpdateMethodBody(project, implementationElement, oldBodyText)
                        return UpdateResult.SyntaxError("Failed after $maxRetries retries. Last error: ${syntaxResult.errorMessage}")
                    }
                    errorMessage = syntaxResult.errorMessage
                }
                is UpdateResult.GenerationError -> return syntaxResult
            }
        }

        return UpdateResult.GenerationError("Unexpected error in retry loop")
    }

    private fun implementationFunctionToLambdaText(function: KtNamedFunction): String {
        val paramsText = function.valueParameters.joinToString(", ") { it.text }
        val bodyText = when (val body = function.bodyExpression) {
            is KtBlockExpression -> body.statements.joinToString("\n") { it.text }
            null -> "return null"
            else -> body.text
        }
        return buildString {
            appendLine(if (paramsText.isBlank()) "{" else "{ $paramsText ->")
            appendLine(bodyText)
            append("}")
        }
    }

    private fun findGeneratedImplementationLambda(property: KtProperty): KtLambdaExpression? {
        (property.getter?.bodyExpression as? KtLambdaExpression)?.let { return it }

        property.delegate?.let { delegate ->
            val lazyBody = PsiTreeUtil.findChildOfType(delegate, KtLambdaExpression::class.java)
                ?: return null
            return PsiTreeUtil.findChildOfType(lazyBody.bodyExpression, KtLambdaExpression::class.java, true)
        }

        return when (val initializer = property.initializer) {
            is KtLambdaExpression -> initializer
            null -> null
            else -> PsiTreeUtil.findChildOfType(initializer, KtLambdaExpression::class.java)
        }
    }

    fun composeAsLlmCaseChain(
        project: Project,
        oldBodyText: String,
        cases: List<KoogLlmClient.AsLlmCaseUpdate>,
        parameterTypesByName: Map<String, String> = emptyMap(),
        toolbeltFunctions: List<String> = emptyList(),
        fallbackBodyText: String? = null,
    ): String {
        return runReadAction {
            val psiFactory = KtPsiFactory(project)
            val oldLambda = psiFactory.createExpression(oldBodyText) as? KtLambdaExpression
                ?: error("Existing implementation is not a lambda expression")
            val fallbackStatements = fallbackBodyText
                ?.let { psiFactory.createExpression(it) as? KtLambdaExpression }
                ?.bodyExpression
                ?.statements
                ?.map { it.text }
                ?.takeIf { it.isNotEmpty() }

            val params = oldLambda.valueParameters
            val paramNames = params.mapNotNull { it.name }
            if (paramNames.size != params.size) {
                error("Existing lambda parameters must be explicitly named")
            }
            val outerParamsText = params.joinToString(", ") { it.text }
            val functionParamsText = params.joinToString(", ") { parameter ->
                val name = parameter.name ?: error("Existing lambda parameters must be explicitly named")
                val typeText = parameter.typeReference?.text
                    ?: parameterTypesByName[name]
                    ?: "Any?"
                "$name: $typeText"
            }
            val invocationArgs = paramNames.joinToString(", ")
            val generatedCases = cases.mapIndexed { idx, case ->
                AsLlmGeneratedCase(
                    index = idx,
                    guardExpression = case.guardExpression,
                    caseHandlerBody = case.caseHandlerBody,
                )
            }

            cases.forEachIndexed { idx, case ->
                psiFactory.createExpression(case.guardExpression.withoutLeadingGuardDocComment())
                psiFactory.createFunction(
                    """
                    fun __kotlinLlmCase$idx(${functionParamsText}): Any? {
                        ${case.caseHandlerBody}
                    }
                    """.trimIndent()
                )
            }

            val toolbeltBlock = renderAsLlmToolbeltBlock(toolbeltFunctions)
            val casesBlock = renderAsLlmCasesBlock(generatedCases, functionParamsText)
            val chainWithFallback = if (fallbackStatements == null) {
                renderAsLlmCaseChain(generatedCases, functionParamsText, invocationArgs)
            } else {
                val chain = renderAsLlmCaseChain(generatedCases, functionParamsText, invocationArgs, returnOnComplete = false)
                buildString {
                    appendIndentedBlock(chain)
                    appendLine("if (__kotlinLlmResult != null) return __kotlinLlmResult")
                    fallbackStatements.forEach { statement ->
                        statement.lineSequence().forEach { line ->
                            appendLine(line)
                        }
                    }
                }
            }

            buildString {
                appendLine("{ $outerParamsText ->")
                appendIndentedBlock(toolbeltBlock)
                appendIndentedBlock(casesBlock)
                appendIndentedBlock(chainWithFallback)
                append("}")
            }
        }
    }

    fun extractAsLlmToolbeltFunctions(project: Project, bodyText: String): List<String> {
        val section = extractMarkedSection(bodyText) ?: return emptyList()
        return runReadAction {
            val psiFactory = KtPsiFactory(project)
            runCatching {
                psiFactory
                    .createFile(section)
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .filterNot { it.name?.startsWith("__kotlinLlm") == true }
                    .map { it.text.trim() }
            }.getOrDefault(emptyList())
        }
    }

    fun extractAsLlmCaseUpdates(
        project: Project,
        bodyText: String,
    ): List<KoogLlmClient.AsLlmCaseUpdate> {
        return runReadAction {
            val psiFactory = KtPsiFactory(project)
            val lambda = runCatching { psiFactory.createExpression(bodyText) as? KtLambdaExpression }
                .getOrNull()
                ?: return@runReadAction emptyList()
            val statements = lambda.bodyExpression?.statements.orEmpty()
            extractAsLlmGeneratedCases(statements)
                .map { case ->
                    KoogLlmClient.AsLlmCaseUpdate(
                        guardExpression = case.guardExpression,
                        caseHandlerBody = case.caseHandlerBody,
                    )
                }
        }
    }

    /**
     * Attempts to update the method body with new code and validates syntax.
     */
    private suspend fun tryUpdateMethodBody(
        project: Project,
        implementationPropertyPsi: PsiElement,
        newBodyText: String
    ): UpdateResult {
        var syntaxError: String? = null

        val normalizedNewBodyText = newBodyText.trim()

        edtWriteAction {
            WriteCommandAction.runWriteCommandAction(project) {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                val psiFactory = KtPsiFactory(project)

                val newBodyExpression = try {
                    psiFactory.createExpression(normalizedNewBodyText)
                } catch (e: Exception) {
                    syntaxError = "Syntax error: ${e.message}"
                    return@runWriteCommandAction
                }
                if (newBodyExpression !is KtLambdaExpression) {
                    syntaxError = "Generated implementation must be a lambda expression"
                    return@runWriteCommandAction
                }

                when (implementationPropertyPsi) {
                    is KtNamedFunction -> {
                        val functionName = implementationPropertyPsi.name ?: run {
                            syntaxError = "Generated implementation function has no name"
                            return@runWriteCommandAction
                        }
                        val returnType = implementationPropertyPsi.typeReference?.text ?: run {
                            syntaxError = "Generated implementation function '$functionName' has no explicit return type"
                            return@runWriteCommandAction
                        }
                        val argumentTypeTexts = implementationPropertyPsi.valueParameters.map { parameter ->
                            parameter.typeReference?.text
                        }
                        if (argumentTypeTexts.any { it == null }) {
                            syntaxError = "Generated implementation function '$functionName' has a parameter without explicit type"
                            return@runWriteCommandAction
                        }
                        val parametersText = implementationPropertyPsi.valueParameters.joinToString(", ") { it.text }
                        val bodyText = newBodyExpression.bodyExpression
                            ?.statements
                            ?.joinToString("\n") { it.text }
                            ?: "return null"
                        val newFunction = try {
                            psiFactory.createFunction(
                                """
                                private fun $functionName($parametersText): $returnType {
                                ${bodyText.lineSequence().joinToString("\n") { "    $it" }}
                                }
                                """.trimIndent()
                            )
                        } catch (e: Exception) {
                            syntaxError = "Syntax error while creating implementation function: ${e.message}"
                            return@runWriteCommandAction
                        }
                        implementationPropertyPsi.replace(newFunction)
                    }

                    is KtProperty -> {
                        val propertyName = implementationPropertyPsi.name ?: run {
                            syntaxError = "Generated implementation property has no name"
                            return@runWriteCommandAction
                        }
                        val typeText = implementationPropertyPsi.typeReference?.text ?: run {
                            syntaxError = "Generated implementation property '$propertyName' has no explicit type"
                            return@runWriteCommandAction
                        }
                        val newProperty = try {
                            psiFactory.createProperty(
                                """
                                private val $propertyName: $typeText
                                    get() = $normalizedNewBodyText
                                """.trimIndent()
                            )
                        } catch (e: Exception) {
                            syntaxError = "Syntax error while creating getter implementation property: ${e.message}"
                            return@runWriteCommandAction
                        }
                        implementationPropertyPsi.replace(newProperty)
                    }

                    else -> {
                        syntaxError = "Could not find generated implementation function or property"
                        return@runWriteCommandAction
                    }
                }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
        }

        return if (syntaxError != null) {
            UpdateResult.SyntaxError(syntaxError)
        } else {
            UpdateResult.Success
        }
    }

    private fun extractMarkedSection(
        text: String,
        startMarker: String = AS_LLM_TOOLBELT_START,
        endMarker: String = AS_LLM_TOOLBELT_END
    ): String? {
        val start = text.indexOf(startMarker).takeIf { it >= 0 } ?: return null
        val contentStart = start + startMarker.length
        val end = text.indexOf(endMarker, contentStart).takeIf { it >= 0 } ?: return null
        return text.substring(contentStart, end).trim()
    }

    private fun StringBuilder.appendIndentedBlock(text: String, indentSize: Int = 4) {
        val indent = " ".repeat(indentSize)
        text.lineSequence().forEach { line ->
            append(indent)
            appendLine(line)
        }
    }

}
