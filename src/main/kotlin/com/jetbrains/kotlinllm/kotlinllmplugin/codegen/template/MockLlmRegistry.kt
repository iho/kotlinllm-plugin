package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.childrenOfType
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualType
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedInterface
import com.jetbrains.kotlinllm.kotlinllmplugin.models.collectUniqueDependencies
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmCodegenWriteMutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtWhenExpression

private const val ROUTER_FILE_NAME = "KotlinLlmGeneratedMockLlmProvider.kt"
private const val ROUTER_NAME = "resolve"

/**
 * Manages registration of generated mock classes in the router file.
 * Handles both import statements and when expression entries for type routing.
 */
class MockLlmRegistry(private val project: Project) : ClassRouter {
    private val psiFactory = KtPsiFactory(project)
    private val registrations = mutableListOf<Registration>()

    override suspend fun register(mockedInterface: MockedInterface, className: String) {
        registrations.add(Registration(mockedInterface.mockedType(), className))
    }

    suspend fun register(mockedType: ActualType, className: String) {
        registrations.add(Registration(mockedType, className))
    }

    /**
     * Replaces all when entries in the router with the accumulated registrations.
     * This removes old entries that no longer have callsites.
     */
    override suspend fun commitRegistrations() {
        ensureGeneratedMockLlmProviderFile(project) ?: return
        ensureGeneratedBootstrapFile(project)
        project.kotlinLlmCodegenWriteMutex.withLock {
            updateProviderFile()
            registrations.clear()
        }
    }

    private suspend fun updateProviderFile() {
        val routerFile = readAction { findRegistryFile() } ?: return
        val requiredImports = registrations
            .flatMap { registration ->
                collectImportsForType(registration.mockedType)
            }
            .plus(registrations.map { registration -> "$GENERATED_MOCK_LLM_PACKAGE.${registration.className}" })
            .filterNot { it.isBlank() }
            .toSet()
        val entryTexts = registrations.map { registration ->
            "typeOf<${registration.mockedType.renderTypeForTypeOf()}>() -> ${registration.className}()"
        }

        edtWriteAction {
            WriteCommandAction.runWriteCommandAction(project) {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                addMissingImports(routerFile, requiredImports)
                val routerPsi = findRouterPsi() ?: return@runWriteCommandAction
                val whenText = buildString {
                    appendLine("when (referenceType) {")
                    entryTexts.forEach { entry ->
                        appendLine("    $entry")
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

    private fun ActualType?.renderTypeForTypeOf(): String {
        return this?.fullName(fullyQualified = false) ?: "kotlin.Any"
    }

    private fun MockedInterface.mockedType(): ActualType {
        return ActualType(
            name = name,
            typeArguments = typeParametersMap.values.filterNotNull(),
            packageName = packageName,
        )
    }

    private data class Registration(
        val mockedType: ActualType,
        val className: String,
    )
}

fun mockLlmRegistry(project: Project): MockLlmRegistry = MockLlmRegistry(project)
