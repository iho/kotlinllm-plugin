package com.jetbrains.kotlinllm.kotlinllmplugin.modes

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.KoogLlmClient
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.mockLlmLogic
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.mockLlmClassGenerator
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.mockLlmRegistry
import com.jetbrains.kotlinllm.kotlinllmplugin.models.*
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmStatsService
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.idea.base.util.projectScope
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass

private const val MOCK_LLM_FQ_NAME = "com.jetbrains.kotlinllm.mockLlm"
private const val MOCK_LLM_NAME = "mockLlm"
private const val STATE_SNAPSHOT_PARAMETER_NAME = "stateSnapshot"

class MockLlmMode(
    private val project: Project,
    private val agentStatusSink: (String) -> Unit = {},
) : KotlinLlmMode {
    private companion object {
        val LOG: Logger = Logger.getInstance(MockLlmMode::class.java)
    }

    val classGenerator = mockLlmClassGenerator(project)
    val registry = mockLlmRegistry(project)
    private val trackedMethodsInThisMode = mutableSetOf<TrackedMethod>()
    private val accumulatedCasesByMethod = mutableMapOf<TrackedMethod, MutableList<KoogLlmClient.AsLlmCaseUpdate>>()
    private val initializedCaseMethods = mutableSetOf<TrackedMethod>()
    private val toolbeltFunctionsByMethod = mutableMapOf<TrackedMethod, List<String>>()
    private val legacyFallbackBodyByMethod = mutableMapOf<TrackedMethod, String>()
    private val pendingCaseByMethod = mutableMapOf<TrackedMethod, KoogLlmClient.AsLlmCaseUpdate>()
    private val pendingToolbeltFunctionsByMethod = mutableMapOf<TrackedMethod, List<String>>()

    /**
     * Find all calls in the form `RunLLM.mockLlm<T>(*)`.
     * Create a file containing a single Kotlin class implementing interface T.
     */
    private suspend fun findMockLlmCalls(): List<ActualType> = readAction {
        val mockLlmDefinition =
            KotlinFunctionShortNameIndex[MOCK_LLM_NAME, project, project.projectScope()]
                .firstOrNull { it.fqName.toString() == MOCK_LLM_FQ_NAME } ?: return@readAction emptyList()
        val mockLlmCallLocations =
            ReferencesSearch.search(mockLlmDefinition).mapNotNull {
                it.element.parentOfType<KtCallExpression>()
            }
        return@readAction mockLlmCallLocations.mapNotNull { call ->
            val typeArgumentsMap = analyze(call) {
                call.resolveToCall()?.singleFunctionCallOrNull()?.typeArgumentsMapping?.entries?.associate {
                    it.key.name.toString() to toActualType(it.value)
                }
            }?.mapValues { it.value } ?: return@mapNotNull null
            if (typeArgumentsMap.size != 1) return@mapNotNull null
            typeArgumentsMap["T"]
        }
    }

    private suspend fun findMockedInterfaces(types: List<ActualType>): List<MockLlmRequest> = readAction {
        types.mapNotNull { type ->
            val expectedFqName = type.fqNameOrNull()
            val interfaceDefinition = KotlinClassShortNameIndex[type.simpleName(), project, project.projectScope()]
                .firstOrNull {
                    it.fqName?.asString() == expectedFqName
                } as? KtClass ?: return@mapNotNull null
            MockLlmRequest(
                type,
                interfaceDefinition.toMockedInterface().withTypeParameterValues(type.typeArguments)
            )
        }
    }

    private fun MockedInterface.mockLlmName(): String {
        val typesNameCleaned = typeParametersMap.values
            .filterNotNull()
            .joinToString("_") { actualType ->
                actualType.fullName().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            }
        return if (typesNameCleaned.isBlank()) "${name}Mock" else "${name}_${typesNameCleaned}Mock"
    }

    private suspend fun generateMockClass(mockedInterface: MockedInterface) =
        classGenerator.generate(mockedInterface, mockedInterface.mockLlmName())

    override suspend fun setup(): Set<TrackedMethod> {
        DumbService.getInstance(project).waitForSmartMode()
        val mockTypes = findMockLlmCalls()
        val mockRequests = findMockedInterfaces(mockTypes)
        val trackedMethods = mutableSetOf<TrackedMethod>()
        mockRequests.forEach { request ->
            val mockedInterface = request.mockedInterface
            trackedMethods.addAll(
                generateMockClass(mockedInterface)
            )
            registry.register(request.mockedType, mockedInterface.mockLlmName())
        }
        registry.commitRegistrations()
        trackedMethodsInThisMode.addAll(trackedMethods)
        trackedMethods.forEach { trackedMethod ->
            accumulatedCasesByMethod.putIfAbsent(trackedMethod, mutableListOf())
        }
        return trackedMethods
    }

    override suspend fun onMethodCalled(
        trackedMethod: TrackedMethod,
        actualValues: List<ActualValue>,
        compilationError: String?
    ): Boolean {
        if (trackedMethod !in trackedMethodsInThisMode) {
            return true
        }

        val llmClient = KoogLlmClient(project = project, statusSink = agentStatusSink)
        val existingCases = accumulatedCasesByMethod.getOrPut(trackedMethod) { mutableListOf() }

        val result = LlmModeUtils.updateMethodImplementation(
            project,
            trackedMethod,
        ) { oldBodyText, errorMessage ->
            val currentBodyText = oldBodyText ?: error("Missing existing implementation body")
            if (trackedMethod !in initializedCaseMethods) {
                val extractedCases = LlmModeUtils.extractAsLlmCaseUpdates(project, currentBodyText)
                existingCases.addAll(extractedCases)
                toolbeltFunctionsByMethod[trackedMethod] =
                    LlmModeUtils.extractAsLlmToolbeltFunctions(project, currentBodyText)
                if (extractedCases.isEmpty()) {
                    legacyFallbackBodyByMethod[trackedMethod] = currentBodyText
                }
                initializedCaseMethods.add(trackedMethod)
            }
            val feedbackError = compilationError ?: errorMessage
            val retryingAfterFeedback = feedbackError != null
            val caseUpdate = llmClient.mockLlmLogic(
                trackedMethod.actualValuesWithOriginalMockTypes(actualValues),
                trackedMethod.returnType,
                trackedMethod.mockedInterface,
                trackedMethod.mockedMethod,
                previousSubmittedCase = pendingCaseByMethod[trackedMethod]
                    .takeIf { retryingAfterFeedback },
                existingToolbeltFunctions = if (retryingAfterFeedback) {
                    pendingToolbeltFunctionsByMethod[trackedMethod]
                        ?: toolbeltFunctionsByMethod[trackedMethod].orEmpty()
                } else {
                    toolbeltFunctionsByMethod[trackedMethod].orEmpty()
                },
                errorMessage = feedbackError
            )
            pendingCaseByMethod[trackedMethod] = caseUpdate
            pendingToolbeltFunctionsByMethod[trackedMethod] = llmClient.toolbeltFunctionTexts()
            LlmModeUtils.composeAsLlmCaseChain(
                project,
                currentBodyText,
                existingCases + caseUpdate,
                toolbeltFunctions = llmClient.toolbeltFunctionTexts(),
                fallbackBodyText = legacyFallbackBodyByMethod[trackedMethod]
            )
        }

        return when (result) {
            is LlmModeUtils.UpdateResult.Success -> true
            is LlmModeUtils.UpdateResult.SyntaxError -> {
                pendingCaseByMethod.remove(trackedMethod)
                pendingToolbeltFunctionsByMethod.remove(trackedMethod)
                LOG.warn("MockLlm generation failed with syntax error: ${result.errorMessage}")
                false
            }
            is LlmModeUtils.UpdateResult.GenerationError -> {
                pendingCaseByMethod.remove(trackedMethod)
                pendingToolbeltFunctionsByMethod.remove(trackedMethod)
                LOG.warn("MockLlm generation failed: ${result.errorMessage}")
                false
            }
        }
    }

    override suspend fun onMethodCompilationSucceeded(trackedMethod: TrackedMethod) {
        val pendingCase = pendingCaseByMethod.remove(trackedMethod) ?: return
        accumulatedCasesByMethod.getOrPut(trackedMethod) { mutableListOf() }.add(pendingCase)
        pendingToolbeltFunctionsByMethod.remove(trackedMethod)?.let { toolbeltFunctions ->
            toolbeltFunctionsByMethod[trackedMethod] = toolbeltFunctions
        }
        project.kotlinLlmStatsService.recordGeneratedUpdateAccepted("mockLlm", trackedMethod)
    }

    private fun TrackedMethod.actualValuesWithOriginalMockTypes(actualValues: List<ActualValue>): List<ActualValue> {
        val typeArgumentMap = mockedInterface?.typeParametersMap.orEmpty()
        val parameterTypesByName = mockedMethod.parameters.associate { parameter ->
            parameter.name to parameter.type.resolveMockType(typeArgumentMap).fullName(fullyQualified = true)
        }

        return actualValues.map { actualValue ->
            if (actualValue.name == STATE_SNAPSHOT_PARAMETER_NAME) {
                actualValue
            } else {
                actualValue.copy(type = parameterTypesByName[actualValue.name] ?: actualValue.type)
            }
        }
    }

    private fun ActualType.resolveMockType(typeArgumentMap: Map<String, ActualType?>): ActualType {
        typeArgumentMap[name]?.let { resolved ->
            return if (isNullable && !resolved.isNullable) resolved.copy(isNullable = true) else resolved
        }

        return copy(
            typeArguments = typeArguments.map { it.resolveMockType(typeArgumentMap) },
            constructorParameters = constructorParameters.map { it.resolveMockType(typeArgumentMap) },
        )
    }
}

private data class MockLlmRequest(
    val mockedType: ActualType,
    val mockedInterface: MockedInterface,
)
