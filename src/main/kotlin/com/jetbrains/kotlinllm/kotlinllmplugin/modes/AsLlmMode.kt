package com.jetbrains.kotlinllm.kotlinllmplugin.modes

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.KoogLlmClient
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.asLlmLogic
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.asLlmClassGenerator
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.asLlmParserRegistry
import com.jetbrains.kotlinllm.kotlinllmplugin.models.*
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmStatsService
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.idea.base.util.projectScope
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass

private const val AS_LLM_FQ_NAME = "com.jetbrains.kotlinllm.asLlm"
private const val AS_LLM_NAME = "asLlm"
private const val HINT_PARAMETER_NAME = "hint"

private const val AS_LLM_PARSER_INTERFACE_FQ_NAME = "com.jetbrains.kotlinllm.AsLlmParser"
private const val AS_LLM_PARSER_INTERFACE_NAME = "AsLlmParser"

class AsLlmMode(
    private val project: Project,
    private val agentStatusSink: (String) -> Unit = {},
) : KotlinLlmMode {
    private companion object {
        val LOG: Logger = Logger.getInstance(AsLlmMode::class.java)
    }

    val classGenerator = asLlmClassGenerator(project)
    val parserRegistry = asLlmParserRegistry(project)
    private val trackedMethodsInThisMode = mutableSetOf<TrackedMethod>()
    private val accumulatedCasesByMethod = mutableMapOf<TrackedMethod, MutableList<KoogLlmClient.AsLlmCaseUpdate>>()
    private val initializedCaseMethods = mutableSetOf<TrackedMethod>()
    private val toolbeltFunctionsByMethod = mutableMapOf<TrackedMethod, List<String>>()
    private val pendingCaseByMethod = mutableMapOf<TrackedMethod, KoogLlmClient.AsLlmCaseUpdate>()
    private val pendingToolbeltFunctionsByMethod = mutableMapOf<TrackedMethod, List<String>>()

    /**
     * Find all calls in the form `RunLLM.asLlm<F, T>(*)`.
     * Create a file containing a single Kotlin class with only method being
     * ```
     *     fun parse(from: F): T
     * ```
     */
    private suspend fun findParserCalls(): List<Parser> = readAction {
        val asLlmDefinition =
            KotlinFunctionShortNameIndex[AS_LLM_NAME, project, project.projectScope()]
                .firstOrNull { it.fqName.toString() == AS_LLM_FQ_NAME } ?: return@readAction emptyList()
        val asLlmCallLocations =
            ReferencesSearch.search(asLlmDefinition).mapNotNull {
                it.element.parentOfType<KtCallExpression>()
            }
        return@readAction asLlmCallLocations.mapNotNull { call ->
            val typeArgumentsMap = analyze(call) {
                call.resolveToCall()?.singleFunctionCallOrNull()?.typeArgumentsMapping?.entries?.associate {
                    it.key.name.toString() to toActualType(it.value)
                }
            } ?: return@mapNotNull null
            if (typeArgumentsMap.size != 2) return@mapNotNull null
            val fromClass = typeArgumentsMap["F"] ?: return@mapNotNull null
            val toClass = typeArgumentsMap["T"] ?: return@mapNotNull null
            Parser(fromClass, toClass)
        }
    }

    private suspend fun findParserInterface(): MockedInterface? = readAction {
        val parserInterfaceDefinition =
            KotlinClassShortNameIndex[AS_LLM_PARSER_INTERFACE_NAME, project, project.projectScope()]
                .firstOrNull {
                    it.fqName.toString() == AS_LLM_PARSER_INTERFACE_FQ_NAME
                } as? KtClass ?: return@readAction null

        parserInterfaceDefinition.toMockedInterface()
    }

    private fun MockedInterface.asLlmParserName(): String {
        val typesNameCleaned = typeParametersMap.values.joinToString("_") { actualType ->
            actualType?.fullName()?.map { if (it.isLetterOrDigit()) it else '_' }?.joinToString("") ?: ""
        }
        return "${typesNameCleaned}Parser"
    }

    private suspend fun generateParserClass(requestedParser: MockedInterface) =
        classGenerator.generate(requestedParser, requestedParser.asLlmParserName())

    override suspend fun setup(): Set<TrackedMethod> {
        DumbService.getInstance(project).waitForSmartMode()
        val parserTypes = findParserCalls()
        val baseParserInterface = findParserInterface() ?: return emptySet()
        val requestedParsers = parserTypes.map { parser ->
            baseParserInterface.withTypeParameterValues(listOf(parser.fromType, parser.toType))
        }
        val trackedMethods = mutableSetOf<TrackedMethod>()
        requestedParsers.forEach { parser ->
            trackedMethods.addAll(
                generateParserClass(parser)
            )
            parserRegistry.register(parser, parser.asLlmParserName())
        }
        parserRegistry.commitRegistrations()
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
        val actualInputValues = actualValues.filterNot { it.name == HINT_PARAMETER_NAME }
        val hint = actualValues.firstOrNull { it.name == HINT_PARAMETER_NAME }?.value
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
        val existingCases = accumulatedCasesByMethod.getOrPut(trackedMethod) { mutableListOf() }

        val result = LlmModeUtils.updateMethodImplementation(
            project,
            trackedMethod,
        ) { oldBodyText, errorMessage ->
            val currentBodyText = oldBodyText ?: error("Missing existing implementation body")
            if (trackedMethod !in initializedCaseMethods) {
                existingCases.addAll(LlmModeUtils.extractAsLlmCaseUpdates(project, currentBodyText))
                toolbeltFunctionsByMethod[trackedMethod] =
                    LlmModeUtils.extractAsLlmToolbeltFunctions(project, currentBodyText)
                initializedCaseMethods.add(trackedMethod)
            }
            val feedbackError = compilationError ?: errorMessage
            val retryingAfterFeedback = feedbackError != null
            val caseUpdate = llmClient.asLlmLogic(
                actualInputValues,
                trackedMethod.returnType,
                hint,
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
                trackedMethod.actualImplementationParameterTypesByName(),
                llmClient.toolbeltFunctionTexts()
            )
        }

        return when (result) {
            is LlmModeUtils.UpdateResult.Success -> true
            is LlmModeUtils.UpdateResult.SyntaxError -> {
                pendingCaseByMethod.remove(trackedMethod)
                pendingToolbeltFunctionsByMethod.remove(trackedMethod)
                LOG.warn("AsLlm generation failed with syntax error: ${result.errorMessage}")
                false
            }
            is LlmModeUtils.UpdateResult.GenerationError -> {
                pendingCaseByMethod.remove(trackedMethod)
                pendingToolbeltFunctionsByMethod.remove(trackedMethod)
                LOG.warn("AsLlm generation failed: ${result.errorMessage}")
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
        project.kotlinLlmStatsService.recordGeneratedUpdateAccepted("asLlm", trackedMethod)
    }

    private fun TrackedMethod.actualImplementationParameterTypesByName(): Map<String, String> {
        val typeParameterMap = mockedInterface?.typeParametersMap.orEmpty()
        return mockedMethod.parameters
            .filterNot { it.name == HINT_PARAMETER_NAME }
            .associate { parameter ->
                parameter.name to parameter.type.resolveType(typeParameterMap).fullName(fullyQualified = true)
            }
    }

    private fun ActualType.resolveType(typeParametersMap: Map<String, ActualType?>): ActualType {
        typeParametersMap[name]?.let { resolvedType ->
            return resolvedType.copy(isNullable = isNullable || resolvedType.isNullable)
        }
        return copy(
            typeArguments = typeArguments.map { it.resolveType(typeParametersMap) },
            constructorParameters = constructorParameters.map { it.resolveType(typeParametersMap) },
        )
    }
}

data class Parser(
    val fromType: ActualType,
    val toType: ActualType,
)
