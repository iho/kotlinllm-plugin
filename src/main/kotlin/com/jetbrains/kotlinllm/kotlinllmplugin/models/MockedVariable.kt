package com.jetbrains.kotlinllm.kotlinllmplugin.models

import org.jetbrains.kotlin.psi.KtProperty

data class MockedVariable(
    val name: String,
    val type: ActualType,
    val isMutable: Boolean = false,
)

fun KtProperty.toField(): MockedVariable {
    return MockedVariable(
        name = name.toString(),
        type = typeReference?.toActualType() ?: ActualType.UNIT,
        isMutable = isVar,
    )
}
