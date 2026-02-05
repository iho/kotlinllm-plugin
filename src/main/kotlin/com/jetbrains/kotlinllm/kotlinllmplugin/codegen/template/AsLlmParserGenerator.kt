package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template

import com.intellij.openapi.project.Project
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualType
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedInterface
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedMethod
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedVariable
import com.jetbrains.kotlinllm.kotlinllmplugin.modes.TrackedMethod
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory

private const val HINT_PARAMETER_NAME = "hint"

class AsLlmParserGenerator(project: Project) : PsiClassGenerator(project) {
    override val generatedSubfolder: GeneratedSourceSubfolder = GeneratedSourceSubfolder.AS_LLM

    override fun generateClassBody(mockedInterface: MockedInterface, className: String): String {
        return buildString {
            val typeArgumentMap = mockedInterface.typeParametersMap

            mockedInterface.methods.forEach { method ->
                appendIndentedText(indentSize, generateImplementationFunction(method, typeArgumentMap))
                appendLine()
                appendIndentedText(indentSize, generateParserMethod(method, typeArgumentMap))
                appendLine()
                appendIndentedText(indentSize, generateRegenerateMethod(method, typeArgumentMap))
                appendIndentedLine(indentSize, "private data class ParsedResult(val value: ${method.resolvedReturnType(typeArgumentMap)})")
            }
        }
    }

    override fun migrateExistingClass(
        targetClass: KtClass,
        mockedInterface: MockedInterface,
        className: String,
    ) {
        val psiFactory = KtPsiFactory(project)
        val typeArgumentMap = mockedInterface.typeParametersMap

        mockedInterface.methods.forEach { method ->
            targetClass.replaceOrAddImplementationFunction(psiFactory, method, typeArgumentMap)
            targetClass.replaceOrAddFunction(
                psiFactory,
                method.name,
                generateParserMethod(method, typeArgumentMap)
            )
            targetClass.replaceOrAddFunction(
                psiFactory,
                method.trackedMethodName(),
                generateRegenerateMethod(method, typeArgumentMap)
            )
        }
    }

    override fun getTrackedMethods(mockedInterface: MockedInterface, implementationClassName: String): List<TrackedMethod> {
        return mockedInterface.methods.map {
            TrackedMethod(
                it,
                it.actualType.resolveType(mockedInterface.typeParametersMap),
                implementationClassName,
                generatedPackageName,
                it.trackedMethodName(),
                implementationArgumentCount = it.parameters.size,
                implementationArgumentTypeNames = it.regenerateArgumentTypeNames(),
                mockedInterface = mockedInterface
            )
        }
    }

    private fun generateImplementationFunction(
        method: MockedMethod,
        typeArgumentMap: Map<String, ActualType?>,
    ): String {
        return """
            private fun ${method.implementationPropertyName()}(${method.implementationParameters().toParameterList(typeArgumentMap)}): ParsedResult? {
                return null
            }
        """.trimIndent()
    }

    private fun KtClass.replaceOrAddImplementationFunction(
        psiFactory: KtPsiFactory,
        method: MockedMethod,
        typeArgumentMap: Map<String, ActualType?>,
    ) {
        val propertyName = method.implementationPropertyName()
        val implementationParameters = method.implementationParameters()
        val expectedParameterNames = implementationParameters.map { it.name }
        val expectedParameterTypes = implementationParameters.map {
            it.type.resolveType(typeArgumentMap).fullName(fullyQualified = true)
        }
        val expectedTypeText = method.implementationFunctionType()

        val existingProperty = declarations
            .filterIsInstance<KtProperty>()
            .firstOrNull { it.name == propertyName }

        if (existingProperty == null) {
            val existingFunction = declarations
                .filterIsInstance<KtNamedFunction>()
                .firstOrNull { it.name == propertyName }
            if (existingFunction != null) {
                val existingParameterNames = existingFunction.valueParameters.mapNotNull { it.name }
                val existingParameterTypes = existingFunction.valueParameters.map { it.typeReference?.text }
                val existingTypeText = existingFunction.typeReference?.text
                if (
                    existingParameterNames == expectedParameterNames &&
                    existingParameterTypes == expectedParameterTypes &&
                    existingTypeText == expectedTypeText
                ) {
                    return
                }
                existingFunction.replace(psiFactory.createFunction(generateImplementationFunction(method, typeArgumentMap)))
                return
            }

            addDeclaration(psiFactory.createFunction(generateImplementationFunction(method, typeArgumentMap)))
            return
        }

        val existingLambda = existingProperty.findGeneratedImplementationLambda() ?: return
        val migratedLambdaText = migrateImplementationLambda(existingLambda, implementationParameters, typeArgumentMap)
        existingProperty.replace(psiFactory.createFunction(generateImplementationFunction(method, typeArgumentMap, migratedLambdaText)))
    }

    private fun generateImplementationFunction(
        method: MockedMethod,
        typeArgumentMap: Map<String, ActualType?>,
        bodyText: String,
    ): String {
        return """
            private fun ${method.implementationPropertyName()}(${method.implementationParameters().toParameterList(typeArgumentMap)}): ParsedResult? {
        ${bodyText.lineSequence().joinToString("\n") { "    $it" }}
            }
        """.trimIndent()
    }

    private fun migrateImplementationLambda(
        lambda: KtLambdaExpression,
        implementationParameters: List<MockedVariable>,
        typeArgumentMap: Map<String, ActualType?>,
    ): String {
        val statements = lambda.bodyExpression?.statements.orEmpty()
        val toolbeltFunctions = statements
            .filterIsInstance<KtNamedFunction>()
            .filterNot { it.name?.startsWith("__kotlinLlm") == true }
            .filterNot { it.name == "impl" }
            .map { it.text.trim() }
        val cases = extractAsLlmGeneratedCases(statements)

        val functionParamsText = implementationParameters.toParameterList(typeArgumentMap)
        val invocationArgs = implementationParameters.joinToString(", ") { it.name }

        val toolbeltBlock = renderAsLlmToolbeltBlock(toolbeltFunctions)
        val casesBlock = renderAsLlmCasesBlock(cases, functionParamsText)
        val chain = renderAsLlmCaseChain(cases, functionParamsText, invocationArgs)

        return buildString {
            appendIndentedBlock(toolbeltBlock)
            appendIndentedBlock(casesBlock)
            appendIndentedBlock(chain)
        }
    }

    private fun generateParserMethod(
        method: MockedMethod,
        typeArgumentMap: Map<String, ActualType?>,
    ): String {
        val runtimeArgumentNames = method.implementationParameters().joinToString { it.name }
        val regenerateArgumentNames = method.parameters.joinToString {
            if (it.name == HINT_PARAMETER_NAME) it.name else "${it.name}.toString()"
        }
        val dollarArgumentNames = method.parameters.joinToString { "$${it.name}" }
        val resolvedReturnType = method.resolvedReturnType(typeArgumentMap)

        return """
            public override fun ${method.name}(${method.parameters.toParameterList(typeArgumentMap)}): $resolvedReturnType {
                val result = runCatching { ${method.name}_($runtimeArgumentNames) }.getOrNull()
                if (result != null) return result.value
                ${method.trackedMethodName()}($regenerateArgumentNames)
                error("Failed to parse $dollarArgumentNames")
            }
        """.trimIndent()
    }

    private fun generateRegenerateMethod(
        method: MockedMethod,
        typeArgumentMap: Map<String, ActualType?>
    ): String {
        return """
            private fun ${method.trackedMethodName()}(${method.parameters.toRegenerateParameterList(typeArgumentMap)}) {
                return
            }
        """.trimIndent()
    }

    private fun MockedMethod.implementationParameters(): List<MockedVariable> {
        return parameters.filterNot { it.name == HINT_PARAMETER_NAME }
    }

    private fun MockedMethod.regenerateArgumentTypeNames(): List<String> {
        return parameters.map { "java.lang.String" }
    }

    private fun MockedMethod.implementationPropertyName(): String = "${name}_"

    private fun MockedMethod.implementationFunctionType(): String {
        return "ParsedResult?"
    }

    private fun MockedMethod.resolvedReturnType(
        typeArgumentMap: Map<String, ActualType?>
    ): String {
        return actualType.resolveType(typeArgumentMap).fullName(fullyQualified = true)
    }

    private fun List<MockedVariable>.toParameterList(
        typeArgumentMap: Map<String, ActualType?>
    ): String {
        return joinToString {
            "${it.name}: ${it.type.resolveType(typeArgumentMap).fullName(fullyQualified = true)}"
        }
    }

    private fun List<MockedVariable>.toRegenerateParameterList(
        typeArgumentMap: Map<String, ActualType?>
    ): String {
        return joinToString {
            if (it.name == HINT_PARAMETER_NAME) {
                "${it.name}: ${it.type.resolveType(typeArgumentMap).fullName(fullyQualified = true)}"
            } else {
                "${it.name}: kotlin.String"
            }
        }
    }

    private fun KtClass.replaceOrAddFunction(
        psiFactory: KtPsiFactory,
        functionName: String,
        functionText: String
    ) {
        val newFunction = psiFactory.createFunction(functionText)
        val existingFunction = declarations
            .filterIsInstance<KtNamedFunction>()
            .firstOrNull { it.name == functionName }

        if (existingFunction != null) {
            existingFunction.replace(newFunction)
        } else {
            addDeclaration(newFunction)
        }
    }

    private fun KtProperty.findGeneratedImplementationLambda(): KtLambdaExpression? {
        (getter?.bodyExpression as? KtLambdaExpression)?.let { return it }
        (initializer as? KtLambdaExpression)?.let { return it }

        delegate?.let { delegate ->
            val lazyBody = PsiTreeUtil.findChildOfType(delegate, KtLambdaExpression::class.java)
                ?: return null
            return PsiTreeUtil.findChildOfType(lazyBody.bodyExpression, KtLambdaExpression::class.java, true)
        }

        return null
    }

    private fun MockedMethod.trackedMethodName(): String = "${name}Regenerate"

    private fun StringBuilder.appendIndentedText(indent: Int, text: String) {
        text.lineSequence().forEach { line ->
            appendIndentedLine(indent, line)
        }
    }

    private fun StringBuilder.appendIndentedBlock(text: String, indentSize: Int = 4) {
        val indent = " ".repeat(indentSize)
        text.lineSequence().forEach { line ->
            append(indent)
            appendLine(line)
        }
    }

}

fun asLlmClassGenerator(project: Project): ClassGenerator = AsLlmParserGenerator(project)
