package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

private val GENERATED_IMPORT_OPTIMIZATION_FILES = setOf(
    "KotlinLlmGeneratedAsLlmProvider.kt",
    "KotlinLlmGeneratedMockLlmProvider.kt",
    "KotlinLlmBootstrap.kt",
)

suspend fun optimizeGeneratedImports(project: Project) {
    val generatedFiles = readAction {
        GENERATED_IMPORT_OPTIMIZATION_FILES
            .mapNotNull { fileName ->
                findGeneratedFile(project, fileName, GENERATED_CORE_PACKAGE, GeneratedSourceSubfolder.CORE)
            }
            .toList()
            .toTypedArray<PsiFile>()
    }
    if (generatedFiles.isEmpty()) return

    edtWriteAction {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        OptimizeImportsProcessor(project, generatedFiles, null).run()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
    }
}
