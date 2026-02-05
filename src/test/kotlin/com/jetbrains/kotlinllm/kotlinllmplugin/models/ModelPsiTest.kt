package com.jetbrains.kotlinllm.kotlinllmplugin.models

import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class ModelPsiTest : BasePlatformTestCase() {
    fun testMockedInterfaceExtractsPsiShape() {
        val file = configureKotlin(
            """
            package sample.api

            data class Issue(val title: String, val labels: List<String>)

            interface GithubService<T> {
                val status: String
                var retryCount: Int

                fun fetch(owner: String, issue: Issue?): List<Issue>
                fun save(value: T)
            }
            """.trimIndent()
        )

        val mocked = runReadAction {
            file.classNamed("GithubService").toMockedInterface()
        }

        assertEquals("GithubService", mocked.name)
        assertEquals("sample.api", mocked.packageName)
        assertEquals(listOf("T"), mocked.typeParametersMap.keys.toList())

        assertEquals(listOf("status", "retryCount"), mocked.properties.map { it.name })
        assertFalse(mocked.properties.single { it.name == "status" }.isMutable)
        assertTrue(mocked.properties.single { it.name == "retryCount" }.isMutable)

        val fetch = mocked.methods.single { it.name == "fetch" }
        assertEquals("List", fetch.actualType.simpleName())
        assertEquals("Issue", fetch.actualType.typeArguments.single().simpleName())
        assertEquals(listOf("owner", "issue"), fetch.parameters.map { it.name })
        assertEquals("String", fetch.parameters[0].type.simpleName())
        assertEquals("Issue", fetch.parameters[1].type.simpleName())
        assertTrue(fetch.parameters[1].type.isNullable)

        val save = mocked.methods.single { it.name == "save" }
        assertEquals("Unit", save.actualType.simpleName())
        assertEquals("T", save.parameters.single().type.simpleName())
    }

    fun testTypeReferenceExtractsDataClassConstructorAndGenerics() {
        val file = configureKotlin(
            """
            package sample.api

            data class Issue(val title: String, val labels: List<String>)

            class Holder {
                fun accept(issue: Issue?, issues: Map<String, Issue>) = Unit
            }
            """.trimIndent()
        )

        val holder = runReadAction { file.classNamed("Holder") }
        val method = runReadAction {
            holder.body!!.children.filterIsInstance<KtNamedFunction>().single { it.name == "accept" }
        }

        val issueType = runReadAction {
            method.valueParameters[0].typeReference!!.toActualType()
        }
        assertEquals("Issue", issueType.simpleName())
        assertEquals("sample.api", issueType.packageName)
        assertTrue(issueType.isNullable)
        assertEquals("(val title: String, val labels: List<String>)", issueType.constructor)
        assertEquals(listOf("String", "List"), issueType.constructorParameters.map { it.simpleName() })

        val mapType = runReadAction {
            method.valueParameters[1].typeReference!!.toActualType()
        }
        assertEquals("Map", mapType.simpleName())
        assertEquals(listOf("String", "Issue"), mapType.typeArguments.map { it.simpleName() })
        assertFalse(mapType.isNullable)
    }

    private fun configureKotlin(text: String): KtFile {
        return myFixture.configureByText("Sample.kt", text) as KtFile
    }

    private fun KtFile.classNamed(name: String): KtClass {
        return declarations.filterIsInstance<KtClass>().single { it.name == name }
    }
}
