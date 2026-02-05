package com.jetbrains.kotlinllm.kotlinllmplugin.models

import com.intellij.psi.PsiFile
import com.intellij.psi.util.childrenOfType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

data class MockedInterface(
    val name: String,
    val methods: List<MockedMethod>,
    val properties: List<MockedVariable>,
    val packageName: String,
    val containingFile: PsiFile,
    val typeParametersMap: Map<String, ActualType?> = emptyMap()
)

fun KtClass.toMockedInterface(): MockedInterface {
    return MockedInterface(
        name = name.toString(),
        methods = body?.childrenOfType<KtNamedFunction>()?.toList()?.map { it.toMockedMethod() } ?: emptyList(),
        properties = body?.childrenOfType<KtProperty>()?.toList()?.map { it.toField() } ?: emptyList(),
        packageName = containingKtFile.packageFqName.toString(),
        containingFile = containingKtFile,
        typeParametersMap = typeParameters.associate { it.text to null },
    )
}

fun MockedInterface.withTypeParameterValues(typeParameterValues: List<ActualType>): MockedInterface =
    copy(typeParametersMap = typeParametersMap.keys.zip(typeParameterValues).toMap())
