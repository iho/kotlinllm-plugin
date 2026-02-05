package com.jetbrains.kotlinllm.kotlinllmplugin.models

import com.intellij.psi.util.descendantsOfType
import com.intellij.psi.PsiClass
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.idea.completion.reference
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

data class ActualType(
    val name: String,
    val typeArguments: List<ActualType> = emptyList(),
    val constructorParameters: List<ActualType> = emptyList(),
    val constructor: String? = null,
    val packageName: String? = null,
    val isNullable: Boolean = false
) {
    companion object {
        val UNIT = ActualType("Unit", packageName = "kotlin")
    }

    fun fullName(fullyQualified: Boolean = false): String {
        val renderedName = if (fullyQualified) {
            qualifiedRenderedName()
        } else {
            name.removePrefix("<root>.")
        }
        val typeArgs = if (typeArguments.isNotEmpty()) {
            typeArguments.joinToString(", ", prefix = "<", postfix = ">") { it.fullName(fullyQualified) }
        } else ""
        return "$renderedName$typeArgs${if (isNullable) "?" else ""}"
    }

    private fun qualifiedRenderedName(): String {
        val renderedName = name.removePrefix("<root>.")
        val rawName = renderedName.substringBefore('<').substringBefore('?')
        val packageNameNormalized = packageName?.takeIf { it.isNotBlank() && it != "<root>" }
            ?: return renderedName
        val qualifiedName = if (rawName.startsWith("$packageNameNormalized.")) {
            rawName
        } else {
            "$packageNameNormalized.$rawName"
        }
        if (typeArguments.isNotEmpty()) return qualifiedName
        return qualifiedName + renderedName.removePrefix(rawName)
    }

    fun importName(): String {
        return importNameOrNull() ?: name.removePrefix("<root>.")
    }

    fun importNameOrNull(): String? {
        val rawName = name
            .removePrefix("<root>.")
            .substringBefore('<')
            .substringBefore('?')
            .takeIf { it.isNotBlank() }
            ?: return null

        val packageNameNormalized = packageName?.takeIf { it != "<root>" } ?: return null
        if (packageNameNormalized.isBlank()) return rawName
        return if (rawName.startsWith("$packageNameNormalized.")) rawName else "$packageNameNormalized.$rawName"
    }
}

fun ActualType.simpleName(): String = name
    .removePrefix("<root>.")
    .substringBefore('<')
    .substringBefore('?')
    .substringAfterLast('.')

fun ActualType.fqNameOrNull(): String? {
    val rawName = name.removePrefix("<root>.").substringBefore('<').substringBefore('?')
    if (rawName.isBlank()) return null

    val packageNameNormalized = packageName?.takeIf { it != "<root>" }
    if (packageNameNormalized == null) return rawName
    if (packageNameNormalized.isBlank()) return rawName
    return if (rawName.startsWith("$packageNameNormalized.")) rawName else "$packageNameNormalized.$rawName"
}

fun ActualType.collectUniqueDependencies(): Set<ActualType> {
    val result = linkedSetOf<ActualType>()

    fun collect(type: ActualType) {
        if (!result.add(type)) return
        type.typeArguments.forEach { collect(it) }
        type.constructorParameters.forEach { collect(it) }
    }

    collect(this)
    return result
}

fun ActualType.render() = buildString {
    collectUniqueDependencies().forEach { dep ->
        appendLine(dep.fullName(fullyQualified = true))
        if (dep.constructor != null && dep.packageName != "kotlin") {
            appendLine("  constructor: ${dep.constructor}")
        }
        if (dep.constructorParameters.isNotEmpty()) {
            appendLine("  needs: ${dep.constructorParameters.joinToString(", ") { it.fullName(fullyQualified = true) }}")
        }
    }
}

fun KtTypeReference.toActualType(): ActualType {
    runCatching {
        analyze(this) {
            toActualType(this@toActualType.type)
        }
    }.getOrNull()?.let { return it }

    val userType = descendantsOfType<KtUserType>().firstOrNull()
    val referencedName = userType?.referencedName ?: text.substringBefore('<').substringBefore('?')
    val typeArgs = userType?.typeArgumentList?.arguments
        ?.mapNotNull { it.typeReference?.toActualType() }
        .orEmpty()

    val resolved = userType?.referenceExpression?.reference()?.resolve()
    val baseType = when (resolved) {
        is KtClass -> resolved.toActualType(isNullable = text.trimEnd().endsWith("?"))
        is PsiClass -> {
            val fqName = resolved.qualifiedName
            if (fqName.isNullOrBlank()) {
                ActualType(name = referencedName)
            } else {
                val packageName = fqName.substringBeforeLast('.', "")
                val simpleName = fqName.substringAfterLast('.')
                ActualType(
                    name = simpleName,
                    packageName = packageName,
                    isNullable = text.trimEnd().endsWith("?")
                )
            }
        }
        else -> ActualType(name = referencedName)
    }

    val constructorParams = buildList {
        addAll(baseType.constructorParameters)
        addAll(typeArgs)
    }

    return baseType.copy(
        name = baseType.name.ifBlank { referencedName },
        typeArguments = typeArgs,
        constructorParameters = constructorParams.ifEmpty { baseType.constructorParameters },
        isNullable = text.trimEnd().endsWith("?")
    )
}

fun KtClass.toActualType(isNullable: Boolean = false): ActualType {
    val packageFqName = containingKtFile.packageFqName.toString()
    val classFqName = fqName?.asString()?.removePrefix("<root>.")
    val className = name ?: "Unit"
    val packageFromFqName = classFqName
        ?.substringBeforeLast('.', "")
        ?.takeIf { it.isNotBlank() }
    val normalizedPackageName = packageFqName
        .takeIf { it != "<root>" }
        ?: packageFromFqName
        ?: ""
    val renderedName = when {
        classFqName == null -> className
        normalizedPackageName.isBlank() -> classFqName
        classFqName.startsWith("$packageFqName.") -> classFqName.removePrefix("$packageFqName.")
        else -> className
    }

    return when {
        isData() -> {
            val constructorParams = primaryConstructorParameters.mapNotNull { it.typeReference?.toActualType() }
            ActualType(
                name = renderedName,
                constructorParameters = constructorParams,
                constructor = primaryConstructor?.text,
                packageName = normalizedPackageName,
                isNullable = isNullable
            )
        }
        isEnum() -> {
            val constructorParams = primaryConstructorParameters.mapNotNull { it.typeReference?.toActualType() }
            ActualType(
                name = renderedName,
                constructorParameters = constructorParams,
                constructor = text,
                packageName = normalizedPackageName,
                isNullable = isNullable
            )
        }
        else -> ActualType(
            name = renderedName,
            constructor = primaryConstructor?.text,
            packageName = normalizedPackageName,
            isNullable = isNullable
        )
    }
}

fun KaSession.toActualType(kaType: KaType): ActualType {
    if (kaType is KaTypeParameterType) {
        val renderedName = kaType.symbol.name.asString()
        return ActualType(
            name = renderedName,
            packageName = null,
            isNullable = kaType.isMarkedNullable
        )
    }

    val normalizedType = kaType.upperBoundIfFlexible()
    val symbol = normalizedType.symbol
    val ktClass = symbol?.psi as? KtClass
    val psiClass = symbol?.psi as? PsiClass

    val typeArgs = (normalizedType as? KaClassType)?.typeArguments?.mapNotNull { typeArg ->
            typeArg.type?.let { toActualType(it) }
        } ?: emptyList()

    val constructorParams = if (ktClass != null && (ktClass.isData() || ktClass.isEnum())) {
        ktClass.primaryConstructorParameters.mapNotNull { param ->
            param.typeReference?.let { toActualType(it.type) }
        }
    } else emptyList()

    val classId = symbol?.classId
    val packageNameFromClassId = classId
        ?.packageFqName
        ?.asString()
    val relativeNameFromClassId = classId
        ?.relativeClassName
        ?.asString()
        ?.takeIf { it.isNotBlank() }

    val packageNameFromPsi = ktClass
        ?.containingKtFile
        ?.packageFqName
        ?.asString()
        ?.takeIf { it != "<root>" }
        ?: psiClass
            ?.qualifiedName
            ?.substringBeforeLast('.', "")

    val relativeNameFromPsiClass = psiClass
        ?.qualifiedName
        ?.substringAfterLast('.')
        ?.takeIf { it.isNotBlank() }

    return ActualType(
        name = relativeNameFromClassId ?: relativeNameFromPsiClass ?: ktClass?.name ?: "Unit",
        typeArguments = typeArgs,
        constructorParameters = constructorParams,
        constructor = if (ktClass?.isData() == true) ktClass.primaryConstructor?.text
                         else if (ktClass?.isEnum() == true) ktClass.text else null,
        packageName = packageNameFromClassId ?: packageNameFromPsi ?: "",
        isNullable = kaType.isMarkedNullable
    )
}
