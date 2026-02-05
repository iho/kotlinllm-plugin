package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template

import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualType
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedInterface
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedMethod
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedVariable
import com.jetbrains.kotlinllm.kotlinllmplugin.models.toSyntheticGetterMethod
import com.jetbrains.kotlinllm.kotlinllmplugin.modes.TrackedMethod
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory

class MockLlmGenerator(project: Project) : PsiClassGenerator(project) {
    override val generatedSubfolder: GeneratedSourceSubfolder = GeneratedSourceSubfolder.MOCK_LLM

    override fun generateClassBody(mockedInterface: MockedInterface, className: String): String {
        return buildString {
            val typeArgumentMap = mockedInterface.typeParametersMap

            appendIndentedLine(indentSize, STATE_PROPERTY_TEXT)
            appendLine()

            mockedInterface.methods.forEach { method ->
                appendIndentedText(indentSize, generateImplementationFunction(method, typeArgumentMap))
                appendLine()
                appendIndentedText(indentSize, generateMockMethod(method, typeArgumentMap))
                appendLine()
                appendIndentedText(indentSize, generateRegenerateMethod(method))
            }
            mockedInterface.properties.forEach { property ->
                val getterMethod = property.toSyntheticGetterMethod(mockedInterface.packageName)
                appendIndentedText(indentSize, generateImplementationFunction(getterMethod, typeArgumentMap))
                appendLine()
                appendIndentedText(indentSize, generateMockProperty(property, getterMethod, typeArgumentMap))
                appendLine()
                appendIndentedText(indentSize, generateRegenerateMethod(getterMethod))
            }
            appendIndentedLine(indentSize, "private class ParsedResult(val value: Any?)")
        }
    }

    override fun migrateExistingClass(
        targetClass: KtClass,
        mockedInterface: MockedInterface,
        className: String,
    ) {
        val psiFactory = KtPsiFactory(project)
        val typeArgumentMap = mockedInterface.typeParametersMap

        targetClass.replaceOrAddStateProperty(psiFactory)
        mockedInterface.methods.forEach { method ->
            targetClass.replaceOrAddImplementationFunction(psiFactory, method, typeArgumentMap)
            targetClass.replaceOrAddFunction(
                psiFactory,
                method.name,
                generateMockMethod(method, typeArgumentMap)
            )
            targetClass.replaceOrAddFunction(
                psiFactory,
                method.trackedMethodName(),
                generateRegenerateMethod(method)
            )
        }
        mockedInterface.properties.forEach { property ->
            val getterMethod = property.toSyntheticGetterMethod(mockedInterface.packageName)
            targetClass.replaceOrAddImplementationFunction(psiFactory, getterMethod, typeArgumentMap)
            targetClass.replaceOrAddProperty(
                psiFactory,
                property.name,
                generateMockProperty(property, getterMethod, typeArgumentMap)
            )
            targetClass.replaceOrAddFunction(
                psiFactory,
                getterMethod.trackedMethodName(),
                generateRegenerateMethod(getterMethod)
            )
        }
        targetClass.replaceOrAddParsedResult(psiFactory)
    }

    override fun getTrackedMethods(
        mockedInterface: MockedInterface,
        implementationClassName: String
    ): List<TrackedMethod> {
        val methodTrackers = mockedInterface.methods.map {
            TrackedMethod(
                it,
                it.actualType.resolveType(mockedInterface.typeParametersMap),
                implementationClassName,
                generatedPackageName,
                it.trackedMethodName(),
                implementationArgumentCount = it.parameters.size + 1,
                implementationArgumentTypeNames = it.regenerateParameters().map { parameter -> parameter.type.jvmTypeName() },
                mockedInterface = mockedInterface
            )
        }
        val propertyTrackers = mockedInterface.properties.map { property ->
            val getterMethod = property.toSyntheticGetterMethod(mockedInterface.packageName)
            TrackedMethod(
                getterMethod,
                property.type.resolveType(mockedInterface.typeParametersMap),
                implementationClassName,
                generatedPackageName,
                getterMethod.trackedMethodName(),
                implementationArgumentCount = 1,
                implementationArgumentTypeNames = getterMethod.regenerateParameters().map { parameter -> parameter.type.jvmTypeName() },
                mockedInterface = mockedInterface,
                mockedProperty = property,
            )
        }
        return methodTrackers + propertyTrackers
    }

    private fun generateImplementationFunction(
        method: MockedMethod,
        typeArgumentMap: Map<String, ActualType?>,
    ): String {
        val implementationParameters = method.implementationParameters(typeArgumentMap)
        val typedArguments = implementationParameters.toParameterList()

        return """
            private fun ${method.implementationPropertyName()}($typedArguments): ${method.implementationFunctionType(typeArgumentMap)} {
                return null
            }
        """.trimIndent()
    }

    private fun generateImplementationFunction(
        method: MockedMethod,
        typeArgumentMap: Map<String, ActualType?>,
        bodyText: String,
    ): String {
        return """
            private fun ${method.implementationPropertyName()}(${method.implementationParameters(typeArgumentMap).toParameterList()}): ${method.implementationFunctionType(typeArgumentMap)} {
        ${bodyText.lineSequence().joinToString("\n") { "    $it" }}
            }
        """.trimIndent()
    }

    private fun generateMockMethod(
        method: MockedMethod,
        typeArgumentMap: Map<String, ActualType?>,
    ): String {
        val implementationArgumentNames = method.implementationInvocationArguments()
        val regenerateArgumentNames = method.regenerateInvocationArguments()
        val dollarArgumentNames = method.parameters.joinToString { "$${it.name}" }
        val resolvedMethodType = method.actualType.resolveType(typeArgumentMap).fullName(fullyQualified = true)

        return """
            public override fun ${method.name}(${method.parameters.toParameterList(typeArgumentMap)}): $resolvedMethodType {
                val result = runCatching { ${method.implementationPropertyName()}($implementationArgumentNames) }.getOrNull()
                if (result != null) return result.value as $resolvedMethodType
                ${method.trackedMethodName()}($regenerateArgumentNames)
                error("Failed to mock ${method.name}($dollarArgumentNames)")
            }
        """.trimIndent()
    }

    private fun generateMockProperty(
        property: MockedVariable,
        getterMethod: MockedMethod,
        typeArgumentMap: Map<String, ActualType?>,
    ): String {
        val resolvedPropertyType = property.type.resolveType(typeArgumentMap).fullName(fullyQualified = true)
        val mutabilityKeyword = if (property.isMutable) "var" else "val"
        val setterText = if (property.isMutable) {
            """

                set(value) {
                    $STATE_PROPERTY_NAME[${property.name.quoteAsKotlinString()}] = value
                }
            """.trimEnd()
        } else {
            ""
        }

        return """
            public override $mutabilityKeyword ${property.name}: $resolvedPropertyType
                get() {
                    val result = runCatching { ${getterMethod.implementationPropertyName()}(${getterMethod.implementationInvocationArguments()}) }.getOrNull()
                    if (result != null) return result.value as $resolvedPropertyType
                    ${getterMethod.trackedMethodName()}(${getterMethod.regenerateInvocationArguments()})
                    error("Failed to mock property ${property.name}")
                }$setterText
        """.trimIndent()
    }

    private fun generateRegenerateMethod(method: MockedMethod): String {
        return """
            private fun ${method.trackedMethodName()}(${method.regenerateParameters().toParameterList()}) {
                return
            }
        """.trimIndent()
    }

    private fun KtClass.replaceOrAddStateProperty(psiFactory: KtPsiFactory) {
        val existingProperty = declarations
            .filterIsInstance<KtProperty>()
            .firstOrNull { it.name == STATE_PROPERTY_NAME }
        if (existingProperty == null) {
            addDeclaration(psiFactory.createProperty(STATE_PROPERTY_TEXT))
        }
    }

    private fun KtClass.replaceOrAddImplementationFunction(
        psiFactory: KtPsiFactory,
        method: MockedMethod,
        typeArgumentMap: Map<String, ActualType?>,
    ) {
        val propertyName = method.implementationPropertyName()
        val implementationParameters = method.implementationParameters(typeArgumentMap)
        val expectedParameterNames = implementationParameters.map { it.name }
        val expectedTypeText = method.implementationFunctionType(typeArgumentMap)

        val existingProperty = declarations
            .filterIsInstance<KtProperty>()
            .firstOrNull { it.name == propertyName }

        if (existingProperty == null) {
            val existingFunction = declarations
                .filterIsInstance<KtNamedFunction>()
                .firstOrNull { it.name == propertyName }
            if (existingFunction != null) {
                val existingParameterNames = existingFunction.valueParameters.mapNotNull { it.name }
                if (
                    existingParameterNames == expectedParameterNames &&
                    existingFunction.typeReference?.text == expectedTypeText
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
        val migratedBodyText = migrateImplementationLambda(existingLambda, implementationParameters)
        existingProperty.replace(psiFactory.createFunction(generateImplementationFunction(method, typeArgumentMap, migratedBodyText)))
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

    private fun KtClass.replaceOrAddProperty(
        psiFactory: KtPsiFactory,
        propertyName: String,
        propertyText: String
    ) {
        val newProperty = psiFactory.createProperty(propertyText)
        val existingProperty = declarations
            .filterIsInstance<KtProperty>()
            .firstOrNull { it.name == propertyName }

        if (existingProperty != null) {
            existingProperty.replace(newProperty)
        } else {
            addDeclaration(newProperty)
        }
    }

    private fun KtClass.replaceOrAddParsedResult(psiFactory: KtPsiFactory) {
        val existingClass = declarations
            .filterIsInstance<KtClass>()
            .firstOrNull { it.name == "ParsedResult" }
        if (existingClass == null) {
            addDeclaration(psiFactory.createClass("private class ParsedResult(val value: Any?)"))
        }
    }

    private fun migrateImplementationLambda(
        lambda: KtLambdaExpression,
        implementationParameters: List<MockedVariable>,
    ): String {
        val statements = lambda.bodyExpression?.statements.orEmpty()
        val toolbeltFunctions = statements
            .filterIsInstance<KtNamedFunction>()
            .filterNot { it.name?.startsWith("__kotlinLlm") == true }
            .filterNot { it.name == "impl" }
            .map { it.text.trim() }
        val cases = extractAsLlmGeneratedCases(statements)

        val functionParamsText = implementationParameters.toParameterList()
        val invocationArgs = implementationParameters.joinToString(", ") { it.name }

        val toolbeltBlock = renderAsLlmToolbeltBlock(toolbeltFunctions)
        val casesBlock = renderAsLlmCasesBlock(cases, functionParamsText)
        val fallbackStatements = statements.map { it.text }.takeIf { cases.isEmpty() && statements.isNotEmpty() }
        val chainWithFallback = if (fallbackStatements == null) {
            renderAsLlmCaseChain(cases, functionParamsText, invocationArgs)
        } else {
            val chain = renderAsLlmCaseChain(cases, functionParamsText, invocationArgs, returnOnComplete = false)
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

        return buildString {
            appendIndentedBlock(toolbeltBlock)
            appendIndentedBlock(casesBlock)
            appendIndentedBlock(chainWithFallback)
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

    private fun MockedMethod.implementationParameters(typeArgumentMap: Map<String, ActualType?>): List<MockedVariable> {
        return listOf(STATE_PARAMETER, STATE_SNAPSHOT_PARAMETER) + parameters.map { parameter ->
            parameter.copy(type = parameter.type.resolveType(typeArgumentMap))
        }
    }

    private fun MockedMethod.regenerateParameters(): List<MockedVariable> {
        return listOf(STATE_SNAPSHOT_PARAMETER) + parameters.map { parameter ->
            parameter.copy(type = STRING_TYPE)
        }
    }

    private fun MockedMethod.implementationInvocationArguments(): String {
        return (listOf(STATE_PROPERTY_NAME, "$STATE_PROPERTY_NAME.toString()") + parameters.map { it.name }).joinToString()
    }

    private fun MockedMethod.regenerateInvocationArguments(): String {
        return (listOf("$STATE_PROPERTY_NAME.toString()") + parameters.map { "${it.name}.toString()" }).joinToString()
    }

    private fun MockedMethod.implementationPropertyName(): String = "${name}_"

    private fun MockedMethod.implementationFunctionType(typeArgumentMap: Map<String, ActualType?>): String {
        return "ParsedResult?"
    }

    private fun MockedMethod.trackedMethodName(): String = "${name}Regenerate"

    private fun String.quoteAsKotlinString(): String {
        return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    private fun List<MockedVariable>.toParameterList(): String {
        return joinToString {
            "${it.name}: ${it.type.fullName(fullyQualified = true)}"
        }
    }

    private fun List<MockedVariable>.toParameterList(
        typeArgumentMap: Map<String, ActualType?>
    ): String {
        return joinToString {
            "${it.name}: ${it.type.resolveType(typeArgumentMap).fullName(fullyQualified = true)}"
        }
    }

    private fun ActualType.jvmTypeName(): String {
        return when (fullName(fullyQualified = true).removeSuffix("?")) {
            "kotlin.String" -> "java.lang.String"
            else -> fullName(fullyQualified = true).removeSuffix("?")
        }
    }

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

    private companion object {
        private const val STATE_PROPERTY_NAME = "state"
        private const val STATE_PROPERTY_TEXT = "private val state: MutableMap<Any?, Any?> = mutableMapOf()"
        private val ANY_NULLABLE = ActualType("Any", packageName = "kotlin", isNullable = true)
        private val STATE_PARAMETER = MockedVariable(
            STATE_PROPERTY_NAME,
            ActualType(
                name = "MutableMap",
                typeArguments = listOf(ANY_NULLABLE, ANY_NULLABLE),
                packageName = "kotlin.collections"
            )
        )
        private val STRING_TYPE = ActualType("String", packageName = "kotlin")
        private val STATE_SNAPSHOT_PARAMETER = MockedVariable(
            "stateSnapshot",
            STRING_TYPE
        )
    }
}

fun mockLlmClassGenerator(project: Project): ClassGenerator = MockLlmGenerator(project)
