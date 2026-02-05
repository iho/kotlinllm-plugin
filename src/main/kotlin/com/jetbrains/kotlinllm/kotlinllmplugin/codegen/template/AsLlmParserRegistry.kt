package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.childrenOfType
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualType
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedInterface
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedMethod
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedVariable
import com.jetbrains.kotlinllm.kotlinllmplugin.models.collectUniqueDependencies
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmCodegenWriteMutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.psi.*

private const val ROUTER_FILE_NAME = "KotlinLlmGeneratedAsLlmProvider.kt"
private const val ROUTER_NAME = "resolve"

/**
 * Manages registration of generated parser classes in the router file.
 * Handles both import statements and when expression entries for type routing.
 */
class AsLlmParserRegistry(private val project: Project): ClassRouter {
    private val psiFactory = KtPsiFactory(project)
    private val registrations = mutableListOf<Pair<MockedInterface, String>>()

    override suspend fun register(mockedInterface: MockedInterface, className: String) {
        registrations.add(mockedInterface to className)
    }

    /**
     * Replaces all when entries in the router with the accumulated registrations.
     * This removes old entries that no longer have callsites.
     */
    override suspend fun commitRegistrations() {
        ensureGeneratedAsLlmProviderFile(project) ?: return
        ensureTypesMatchHelper()
        ensureGeneratedBootstrapFile(project)
        project.kotlinLlmCodegenWriteMutex.withLock {
            updateProviderFile()
            registrations.clear()
        }
    }

    private suspend fun updateProviderFile() {
        val routerFile = readAction { findRegistryFile() } ?: return
        val requiredImports = registrations
            .flatMap { (mockedInterface, _) ->
                val fromType = mockedInterface.typeParametersMap["F"]
                    ?: mockedInterface.typeParametersMap.values.firstOrNull()
                val toType = mockedInterface.typeParametersMap["T"]
                    ?: mockedInterface.typeParametersMap.values.drop(1).firstOrNull()
                collectImportsForType(fromType) + collectImportsForType(toType)
            }
            .plus(registrations.map { (_, className) -> "$GENERATED_AS_LLM_PACKAGE.$className" })
            .filterNot { it.isBlank() }
            .toSet()
        val entryTexts = registrations.map { (mockedInterface, className) ->
            val fromType = mockedInterface.typeParametersMap["F"] ?: mockedInterface.typeParametersMap.values.firstOrNull()
            val toType = mockedInterface.typeParametersMap["T"] ?: mockedInterface.typeParametersMap.values.drop(1).firstOrNull()
            val fromTypeText = fromType.renderTypeForTypeOf()
            val toTypeText = toType.renderTypeForTypeOf()
            buildString {
                append("typesMatch(fromType, typeOf<$fromTypeText>()) && typesMatch(toType, typeOf<$toTypeText>()) -> ")
                append(renderRetryingParser(mockedInterface, className, fromTypeText, toTypeText))
            }
        }

        edtWriteAction {
            WriteCommandAction.runWriteCommandAction(project) {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                addMissingImports(routerFile, requiredImports)
                val routerPsi = findRouterPsi() ?: return@runWriteCommandAction
                val whenText = buildString {
                    appendLine("when {")
                    entryTexts.forEach { entry ->
                        entry.lineSequence().forEach { line ->
                            appendLine("    $line")
                        }
                    }
                    appendLine("    else -> null")
                    append("}")
                }
                val newWhen = psiFactory.createExpression(whenText) as? KtWhenExpression
                    ?: return@runWriteCommandAction
                routerPsi.replace(newWhen)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
        }
    }

    private fun collectImportsForType(type: ActualType?): Set<String> {
        if (type == null) return emptySet()
        return linkedSetOf(type)
            .apply { addAll(type.collectUniqueDependencies()) }
            .mapNotNull { it.importNameOrNull() }
            .toSet()
    }

    private fun addMissingImports(file: KtFile, requiredImports: Set<String>) {
        val existingImports = file.importDirectives.mapNotNull { it.importedFqName?.asString() }.toSet()
        val missingImports = requiredImports.minus(existingImports)
        if (missingImports.isEmpty()) return

        val importList = file.importList
        var anchor = file.packageDirective ?: file.firstChild
        missingImports.sorted().forEach { fqName ->
            val importDirective = psiFactory.createFile("import $fqName")
                .importDirectives
                .firstOrNull()
                ?: return@forEach
            if (importList != null) {
                importList.add(importDirective)
            } else {
                anchor = file.addAfter(importDirective, anchor)
            }
        }
    }

    private fun renderRetryingParser(
        mockedInterface: MockedInterface,
        className: String,
        fromTypeText: String,
        toTypeText: String,
    ): String {
        val typeArgumentMap = mockedInterface.typeParametersMap
        return buildString {
            appendLine("object : AsLlmParser<$fromTypeText, $toTypeText> {")
            mockedInterface.methods.forEach { method ->
                appendIndentedBlock(renderRetryingMethod(method, typeArgumentMap, className), indent = 4)
            }
            append("}")
        }.trimEnd()
    }

    private fun renderRetryingMethod(
        method: MockedMethod,
        typeArgumentMap: Map<String, ActualType?>,
        className: String,
    ): String {
        val argumentNames = method.parameters.joinToString { it.name }
        val dollarArgumentNames = method.parameters.joinToString { "$${it.name}" }
        val returnType = method.actualType.resolveType(typeArgumentMap).fullName(fullyQualified = true)

        return """
            public override fun ${method.name}(${method.parameters.toParameterList(typeArgumentMap)}): $returnType {
                return retryingParse("Failed to parse $dollarArgumentNames") {
                    $className().${method.name}($argumentNames)
                }
            }
        """.trimIndent()
    }

    private fun findRouterPsi(): KtWhenExpression? {
        val routerPsiFile = findRegistryFile() ?: return null
        val resolveFunction = routerPsiFile
            .childrenOfType<KtObjectDeclaration>()
            .firstOrNull()?.body
            ?.childrenOfType<KtNamedFunction>()
            ?.firstOrNull { it.name == ROUTER_NAME }
            ?: return null

        val bodyExpression = resolveFunction.bodyExpression ?: return null
        return (bodyExpression as? KtWhenExpression)
            ?: bodyExpression.childrenOfType<KtWhenExpression>().firstOrNull()
            ?: PsiTreeUtil.findChildOfType(bodyExpression, KtWhenExpression::class.java)
    }

    private fun findRegistryFile(): KtFile? {
        return findGeneratedFile(project, ROUTER_FILE_NAME, GENERATED_CORE_PACKAGE, GeneratedSourceSubfolder.CORE)
    }

    private suspend fun ensureTypesMatchHelper() {
        val providerFile = readAction { findRegistryFile() } ?: return
        val content = readAction { providerFile.text }
        if (content.contains("private fun typesMatch(")) return

        val helper = """

private fun typesMatch(first: KType, second: KType): Boolean {
    if (first.classifier != second.classifier) return false
    if (first.arguments.size != second.arguments.size) return false

    return first.arguments.zip(second.arguments).all { (arg1, arg2) ->
        val type1 = arg1.type
        val type2 = arg2.type
        if (type1 == null || type2 == null) return@all type1 == type2
        typesMatch(type1, type2)
    }
}
""".trimIndent()

        edtWriteAction {
            WriteCommandAction.runWriteCommandAction(project) {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                VfsUtil.saveText(providerFile.virtualFile, content.trimEnd() + "\n" + helper + "\n")
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
        }
    }

    private fun ActualType?.renderTypeForTypeOf(): String {
        return this?.fullName(fullyQualified = true) ?: "kotlin.Any"
    }

    private fun List<MockedVariable>.toParameterList(
        typeArgumentMap: Map<String, ActualType?>
    ): String {
        return joinToString {
            "${it.name}: ${it.type.resolveType(typeArgumentMap).fullName(fullyQualified = true)}"
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

    private fun StringBuilder.appendIndentedBlock(text: String, indent: Int) {
        val prefix = " ".repeat(indent)
        text.lineSequence().forEach { line ->
            append(prefix)
            appendLine(line)
        }
    }
}

fun asLlmParserRegistry(project: Project): ClassRouter = AsLlmParserRegistry(project)
