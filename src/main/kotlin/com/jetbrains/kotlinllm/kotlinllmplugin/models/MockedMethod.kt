package com.jetbrains.kotlinllm.kotlinllmplugin.models

import org.jetbrains.kotlin.psi.KtNamedFunction

data class MockedMethod(
    val name: String,
    val actualType: ActualType,
    val parameters: List<MockedVariable>,
    val packageName: String,
)

fun KtNamedFunction.toMockedMethod(): MockedMethod {

    return MockedMethod(
        name = name.toString(),
        actualType = typeReference?.toActualType() ?: ActualType.UNIT,
        parameters = valueParameters.map {
            MockedVariable(
                name = it.name.toString(),
                type = it.typeReference?.toActualType() ?: ActualType.UNIT
            )
        },
        packageName = containingKtFile.packageFqName.toString()
    )
}

fun MockedVariable.toSyntheticGetterMethod(packageName: String): MockedMethod {
    return MockedMethod(
        name = "${name}PropertyGetter",
        actualType = type,
        parameters = emptyList(),
        packageName = packageName,
    )
}
