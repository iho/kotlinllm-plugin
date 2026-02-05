package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualType
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedInterface
import com.jetbrains.kotlinllm.kotlinllmplugin.models.collectUniqueDependencies
import com.jetbrains.kotlinllm.kotlinllmplugin.modes.TrackedMethod
import com.jetbrains.kotlinllm.kotlinllmplugin.services.ensureConfiguredKotlinLlmFolder
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.base.util.projectScope
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*

const val DEFAULT_INDENT_SIZE = 4

/**
 * Interface for generating implementation classes from interface definitions.
 */
interface ClassGenerator {
    /**
     * Generates an implementation class for the given interface.
     * @param mockedInterface The interface to implement
     * @param className The name of the generated class
     * @return List of methods that should be tracked for runtime interception
     */
    suspend fun generate(mockedInterface: MockedInterface, className: String): List<TrackedMethod>
}

/**
 * Base class for PSI-based class generators.
 * Handles file creation, imports, and class structure generation.
 */
abstract class PsiClassGenerator(val project: Project): ClassGenerator {

    val indentSize = DEFAULT_INDENT_SIZE

    protected abstract val generatedSubfolder: GeneratedSourceSubfolder

    protected val generatedPackageName: String
        get() = generatedSubfolder.packageName

    /**
     * Generates the class body content (methods, properties, etc.)
     */
    abstract fun generateClassBody(mockedInterface: MockedInterface, className: String): String

    /**
     * Returns the list of methods to track from the generated class.
     */
    abstract fun getTrackedMethods(mockedInterface: MockedInterface, implementationClassName: String): List<TrackedMethod>

    override suspend fun generate(
        mockedInterface: MockedInterface,
        className: String
    ): List<TrackedMethod> {
        val fileName = "$className.kt"
        val prelude = generatePrelude(mockedInterface)

        val fileText = buildString {
            appendLine(prelude)
            appendLine("public class $className : ${mockedInterface.fullName()}${mockedInterface.typeArgumentsRender()} {")
            appendLine(generateClassBody(mockedInterface, className))
            appendLine("}")
        }

        edtWriteAction {
            WriteCommandAction.runWriteCommandAction(project) {
                val root = ensureConfiguredKotlinLlmFolder(project)
                    ?: return@runWriteCommandAction
                val targetFolder = ensureGeneratedSubfolder(project, root, generatedSubfolder)
                val legacyFile = root.findChild(fileName)?.takeIf { !it.isDirectory }
                if (legacyFile != null && targetFolder.findChild(fileName) == null) {
                    legacyFile.move(project, targetFolder)
                } else if (legacyFile != null) {
                    legacyFile.delete(project)
                }

                val directory = PsiManager.getInstance(project).findDirectory(targetFolder)
                    ?: return@runWriteCommandAction
                val existingFile = directory.findFile(fileName)

                if (existingFile == null) {
                    val createdFile = PsiFileFactory.getInstance(project)
                        .createFileFromText(fileName, KotlinFileType.INSTANCE, fileText)
                    directory.add(createdFile) as? PsiFile
                } else {
                    val existingKtFile = existingFile as? KtFile
                    ensurePackageDirective(existingKtFile)
                    ensureImports(existingKtFile, prelude)
                    migrateCachedImplementationProperties(existingKtFile, mockedInterface, className)
                    val targetClass = existingKtFile?.findGeneratedClass(className)
                    if (targetClass != null) {
                        migrateExistingClass(targetClass, mockedInterface, className)
                    }
                }
            }
        }
        return getTrackedMethods(mockedInterface, className)
    }

    protected open fun migrateExistingClass(
        targetClass: KtClass,
        mockedInterface: MockedInterface,
        className: String,
    ) {
    }

    private fun migrateCachedImplementationProperties(
        file: KtFile?,
        mockedInterface: MockedInterface,
        className: String,
    ) {
        if (file == null) return

        val targetClass = file.findGeneratedClass(className)
            ?: return
        val psiFactory = KtPsiFactory(project)

        mockedInterface.methods.forEach { method ->
            val propertyName = "${method.name}_"
            val property = PsiTreeUtil.findChildrenOfType(targetClass, KtProperty::class.java)
                .firstOrNull { it.name == propertyName }
                ?: return@forEach
            if (property.getter?.bodyExpression is KtLambdaExpression && property.delegate == null) {
                return@forEach
            }

            val typeText = property.typeReference?.text ?: return@forEach
            val lambda = findGeneratedImplementationLambda(property) ?: return@forEach
            val migratedProperty = psiFactory.createProperty(
                """
                private val $propertyName: $typeText
                    get() = ${lambda.text}
                """.trimIndent()
            )
            property.replace(migratedProperty)
        }
    }

    private fun findGeneratedImplementationLambda(property: KtProperty): KtLambdaExpression? {
        (property.getter?.bodyExpression as? KtLambdaExpression)?.let { return it }

        property.delegate?.let { delegate ->
            val lazyBody = PsiTreeUtil.findChildOfType(delegate, KtLambdaExpression::class.java)
                ?: return null
            return PsiTreeUtil.findChildOfType(lazyBody.bodyExpression, KtLambdaExpression::class.java, true)
        }

        return when (val initializer = property.initializer) {
            is KtLambdaExpression -> initializer
            null -> null
            else -> PsiTreeUtil.findChildOfType(initializer, KtLambdaExpression::class.java)
        }
    }

    private fun KtFile.findGeneratedClass(className: String): KtClass? {
        return PsiTreeUtil.findChildrenOfType(this, KtClass::class.java)
            .firstOrNull { it.name == className }
    }

    private fun ensureImports(file: KtFile?, prelude: String) {
        if (file == null) return
        removeDuplicateImports(file)

        val requiredImports = prelude
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").trim() }
            .filter { it.isNotBlank() }
            .toSet()
        if (requiredImports.isEmpty()) return

        val existingImports = file.importDirectives
            .mapNotNull { it.importedFqName?.asString() }
            .toSet()
        val missingImports = requiredImports.minus(existingImports)
        if (missingImports.isEmpty()) return

        val psiFactory = KtPsiFactory(project)
        val importList = file.importList
        var anchor = file.packageDirective ?: file.firstChild
        missingImports
            .sorted()
            .forEach { fqName ->
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

    private fun ensurePackageDirective(file: KtFile?) {
        if (file == null || file.packageFqName.asString() == generatedPackageName) return

        val psiFactory = KtPsiFactory(project)
        val packageDirective = psiFactory.createPackageDirective(FqName(generatedPackageName))
        val existingDirective = file.packageDirective
        if (existingDirective != null) {
            existingDirective.replace(packageDirective)
        } else {
            file.addBefore(packageDirective, file.firstChild)
        }
    }

    private fun removeDuplicateImports(file: KtFile) {
        val seenImports = mutableSetOf<Pair<String, Boolean>>()
        file.importDirectives.forEach { directive ->
            val fqName = directive.importedFqName?.asString() ?: return@forEach
            val importKey = fqName to directive.isAllUnder
            if (!seenImports.add(importKey)) {
                directive.delete()
            }
        }
    }

    private fun generatePrelude(mockedInterface: MockedInterface): String = buildString {
        val typeArgumentMap = mockedInterface.typeParametersMap
        val dependencyTypes = mutableSetOf<ActualType>().apply {
            mockedInterface.typeParametersMap.values.forEach { typeParameter ->
                if (typeParameter != null) this.addAll(typeParameter.collectUniqueDependencies())
            }

            mockedInterface.methods.forEach { method ->
                method.parameters.forEach { parameter ->
                    val resolvedParameterType = parameter.type.resolveType(typeArgumentMap)
                    this.addAll(resolvedParameterType.collectUniqueDependencies())
                }

                val resolvedReturnType = method.actualType.resolveType(typeArgumentMap)
                this.addAll(resolvedReturnType.collectUniqueDependencies())
            }
            mockedInterface.properties.forEach { property ->
                val resolvedPropertyType = property.type.resolveType(typeArgumentMap)
                this.addAll(resolvedPropertyType.collectUniqueDependencies())
            }
        }
        val wildcardPackages = dependencyTypes
            .groupBy { it.packageName }
            .mapNotNull { (packageName, typesInPackage) ->
                packageName
                    ?.takeIf { it.isNotBlank() && it != "<root>" }
                    ?.takeIf { _ -> typesInPackage.any { type -> type.name.contains('.') } }
            }
            .toSortedSet()

        val explicitImports = (dependencyTypes
            .filterNot { type ->
                val packageName = type.packageName
                packageName != null && wildcardPackages.contains(packageName)
            }
            .mapNotNull { it.importNameOrNull() }
            .toMutableSet()).apply {
            addAll(collectImportsFromResolvedReturnTypeFiles(mockedInterface))
        }.toSortedSet()

        appendLine("package $generatedPackageName")

        val interfaceFqName = mockedInterface.packageName
            .takeIf { it.isNotBlank() && it != "<root>" }
            ?.let { "$it.${mockedInterface.name}" }
            ?: mockedInterface.name
        appendLine("import $interfaceFqName")

        wildcardPackages.forEach {
            appendLine("import $it.*")
        }

        explicitImports.forEach {
            if (it == interfaceFqName) return@forEach
            appendLine("import $it")
        }
    }

    private fun collectImportsFromResolvedReturnTypeFiles(mockedInterface: MockedInterface): Set<String> {
        return runCatching {
            DumbService.getInstance(project).waitForSmartMode()
            runReadAction {
                val searchScope = GlobalSearchScope.projectScope(project)
                val javaPsiFacade = JavaPsiFacade.getInstance(project)
                val typeArgumentMap = mockedInterface.typeParametersMap
                val methodReturnTypes = mockedInterface.methods
                    .asSequence()
                    .map { it.actualType.resolveType(typeArgumentMap) }
                val propertyTypes = mockedInterface.properties
                    .asSequence()
                    .map { it.type.resolveType(typeArgumentMap) }

                (methodReturnTypes + propertyTypes)
                    .flatMap { returnType ->
                        sequenceOf(returnType) + returnType.collectUniqueDependencies().asSequence()
                    }
                    .distinctBy { it.importName() }
                    .mapNotNull { resolveKtFileForType(it, javaPsiFacade, searchScope) }
                    .flatMap { ktFile -> ktFile.importDirectives.asSequence() }
                    .mapNotNull { directive ->
                        directive.importedFqName
                            ?.takeUnless { directive.isAllUnder }
                            ?.asString()
                    }
                    .toSet()
            }
        }.getOrElse {
            emptySet()
        }
    }

    private fun resolveKtFileForType(
        type: ActualType,
        javaPsiFacade: JavaPsiFacade,
        searchScope: GlobalSearchScope
    ): KtFile? {
        val packageName = type.packageName?.takeIf { it.isNotBlank() && it != "<root>" } ?: return null
        val typeName = type.name.removePrefix("<root>.")
            .substringBefore('<')
            .substringBefore('?')
            .takeIf { it.isNotBlank() }
            ?: return null
        val fullFqName = "$packageName.$typeName"

        val shortName = typeName.substringAfterLast('.')
        val fromKotlinIndex = KotlinClassShortNameIndex[shortName, project, project.projectScope()]
            .firstOrNull { ktClass ->
                ktClass.fqName?.asString() == fullFqName
            }
            ?.containingKtFile
        if (fromKotlinIndex != null) return fromKotlinIndex

        return javaPsiFacade.findClass(fullFqName, searchScope)?.containingFile as? KtFile
    }

    private fun MockedInterface.typeArgumentsRender(): String {
        if (typeParametersMap.isEmpty()) return ""
        return typeParametersMap.values.joinToString(prefix = "<", postfix = ">") {
            it?.fullName(fullyQualified = true) ?: ""
        }
    }

    private fun MockedInterface.fullName(): String {
        return packageName
            .takeIf { it.isNotBlank() && it != "<root>" }
            ?.let { "$it.$name" }
            ?: name
    }

    protected fun ActualType.resolveType(typeParametersMap: Map<String, ActualType?>): ActualType {
        typeParametersMap[name]?.let { resolvedType ->
            return resolvedType.copy(isNullable = isNullable || resolvedType.isNullable)
        }
        return copy(
            typeArguments = typeArguments.map { it.resolveType(typeParametersMap) },
            constructorParameters = constructorParameters.map { it.resolveType(typeParametersMap) },
        )
    }

    protected fun StringBuilder.appendIndentedLine(indent: Int, line: String)  = appendLine(" ".repeat(indent) + line)
}
