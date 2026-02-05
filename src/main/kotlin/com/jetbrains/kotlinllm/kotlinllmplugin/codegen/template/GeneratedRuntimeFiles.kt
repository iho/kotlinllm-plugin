package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.kotlinllm.kotlinllmplugin.services.ensureConfiguredKotlinLlmFolder
import com.jetbrains.kotlinllm.kotlinllmplugin.services.resolveConfiguredKotlinLlmFolder
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.psi.KtFile

const val GENERATED_BASE_PACKAGE = "com.jetbrains.kotlinllm.generated"
const val GENERATED_CORE_PACKAGE = "$GENERATED_BASE_PACKAGE.core"
const val GENERATED_AS_LLM_PACKAGE = "$GENERATED_BASE_PACKAGE.asLlm"
const val GENERATED_MOCK_LLM_PACKAGE = "$GENERATED_BASE_PACKAGE.mockLlm"
private const val AS_LLM_PROVIDER_FILE_NAME = "KotlinLlmGeneratedAsLlmProvider.kt"
private const val MOCK_LLM_PROVIDER_FILE_NAME = "KotlinLlmGeneratedMockLlmProvider.kt"
private const val BOOTSTRAP_FILE_NAME = "KotlinLlmBootstrap.kt"

enum class GeneratedSourceSubfolder(val folderName: String, val packageName: String) {
    CORE("core", GENERATED_CORE_PACKAGE),
    AS_LLM("asLlm", GENERATED_AS_LLM_PACKAGE),
    MOCK_LLM("mockLlm", GENERATED_MOCK_LLM_PACKAGE),
}

private val AS_LLM_PROVIDER_CONTENT = """
@file:Suppress("unused", "unchecked_cast")
package com.jetbrains.kotlinllm.generated.core

import com.jetbrains.kotlinllm.AsLlmParser
import com.jetbrains.kotlinllm.AsLlmProvider
import kotlin.reflect.KType
import kotlin.reflect.typeOf

public object KotlinLlmGeneratedAsLlmProvider : AsLlmProvider {
    public override fun resolve(fromType: KType, toType: KType): AsLlmParser<*, *>? {
        return when {
            else -> null
        }
    }
}

private inline fun <T> retryingParse(message: String, maxRetries: Int = 5, block: () -> T): T {
    var lastFailure: Throwable? = null
    repeat(maxRetries + 1) {
        val result = runCatching(block)
        if (result.isSuccess) return result.getOrThrow()
        lastFailure = result.exceptionOrNull()
    }
    throw IllegalStateException(message, lastFailure)
}

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
""".trimIndent() + "\n"

private val MOCK_LLM_PROVIDER_CONTENT = """
@file:Suppress("unused")
package com.jetbrains.kotlinllm.generated.core

import com.jetbrains.kotlinllm.MockLlmProvider
import kotlin.reflect.KType
import kotlin.reflect.typeOf

public object KotlinLlmGeneratedMockLlmProvider : MockLlmProvider {
    public override fun resolve(referenceType: KType): Any? {
        return when (referenceType) {
            else -> null
        }
    }
}
""".trimIndent() + "\n"

private val BOOTSTRAP_CONTENT = """
@file:Suppress("unused")
package com.jetbrains.kotlinllm.generated.core

import com.jetbrains.kotlinllm.AsLlmManager
import com.jetbrains.kotlinllm.MockLlmManager

public object KotlinLlmBootstrap {
    init {
        AsLlmManager.install(KotlinLlmGeneratedAsLlmProvider)
        MockLlmManager.install(KotlinLlmGeneratedMockLlmProvider)
    }
}
""".trimIndent() + "\n"

suspend fun ensureGeneratedAsLlmProviderFile(project: Project): KtFile? {
    ensureGeneratedFile(project, GeneratedSourceSubfolder.CORE, AS_LLM_PROVIDER_FILE_NAME, AS_LLM_PROVIDER_CONTENT)
    return readAction { findGeneratedFile(project, AS_LLM_PROVIDER_FILE_NAME, GENERATED_CORE_PACKAGE, GeneratedSourceSubfolder.CORE) }
}

suspend fun ensureGeneratedMockLlmProviderFile(project: Project): KtFile? {
    ensureGeneratedFile(project, GeneratedSourceSubfolder.CORE, MOCK_LLM_PROVIDER_FILE_NAME, MOCK_LLM_PROVIDER_CONTENT)
    return readAction { findGeneratedFile(project, MOCK_LLM_PROVIDER_FILE_NAME, GENERATED_CORE_PACKAGE, GeneratedSourceSubfolder.CORE) }
}

suspend fun ensureGeneratedBootstrapFile(project: Project): KtFile? {
    ensureGeneratedFile(project, GeneratedSourceSubfolder.CORE, BOOTSTRAP_FILE_NAME, BOOTSTRAP_CONTENT)
    return readAction { findGeneratedFile(project, BOOTSTRAP_FILE_NAME, GENERATED_CORE_PACKAGE, GeneratedSourceSubfolder.CORE) }
}

private suspend fun ensureGeneratedFile(
    project: Project,
    subfolder: GeneratedSourceSubfolder,
    fileName: String,
    fileContent: String
) {
    val legacy = readAction { findGeneratedFile(project, fileName, GENERATED_BASE_PACKAGE, subfolder = null) }

    val configuredFolder = ensureConfiguredKotlinLlmFolder(project)
        ?: readAction { resolveConfiguredKotlinLlmFolder(project) }
        ?: return
    edtWriteAction {
        WriteCommandAction.runWriteCommandAction(project) {
            val directory = configuredFolder.ensureSubfolder(project, subfolder)
            val targetFile = directory.findChild(fileName) ?: run {
                val legacyFile = legacy?.virtualFile
                if (legacyFile != null) {
                    legacyFile.move(project, directory)
                    directory.findChild(fileName) ?: directory.createChildData(project, fileName)
                } else {
                    directory.findChild(fileName) ?: directory.createChildData(project, fileName)
                }
            }
            legacy?.virtualFile?.takeIf { it.parent == configuredFolder }?.delete(project)
            VfsUtil.saveText(targetFile, fileContent)
        }
    }
}

fun findGeneratedFile(
    project: Project,
    fileName: String,
    packageName: String,
    subfolder: GeneratedSourceSubfolder? = null
): KtFile? {
    val configuredFolder = resolveConfiguredKotlinLlmFolder(project) ?: return null
    val searchFolder = subfolder?.let { configuredFolder.findChild(it.folderName) } ?: configuredFolder
    val configuredFile = searchFolder?.findChild(fileName)?.toPsiFile(project) as? KtFile
    if (configuredFile?.packageDirective?.fqName.toString() == packageName) {
        return configuredFile
    }
    return null
}

fun ensureGeneratedSubfolder(project: Project, root: VirtualFile, subfolder: GeneratedSourceSubfolder): VirtualFile {
    return root.ensureSubfolder(project, subfolder)
}

fun findGeneratedSourceFile(project: Project, fileName: String, packageName: String): VirtualFile? {
    val configuredFolder = resolveConfiguredKotlinLlmFolder(project) ?: return null
    return configuredFolder.collectGeneratedKotlinFiles()
        .firstOrNull { file ->
            if (file.name != fileName) return@firstOrNull false
            val ktFile = file.toPsiFile(project) as? KtFile ?: return@firstOrNull false
            ktFile.packageDirective?.fqName?.asString() == packageName
        }
}

private fun VirtualFile.ensureSubfolder(project: Project, subfolder: GeneratedSourceSubfolder): VirtualFile {
    return findChild(subfolder.folderName)?.takeIf { it.isDirectory }
        ?: createChildDirectory(project, subfolder.folderName)
}

private fun VirtualFile.collectGeneratedKotlinFiles(): List<VirtualFile> {
    return buildList {
        fun visit(file: VirtualFile) {
            if (file.isDirectory) {
                file.children.forEach(::visit)
            } else if (file.extension == "kt") {
                add(file)
            }
        }
        visit(this@collectGeneratedKotlinFiles)
    }
}
