import org.jetbrains.intellij.platform.gradle.extensions.excludeCoroutines

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "com.jetbrains.kotlinllm"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/grazi/grazie-platform-public/")
    intellijPlatform {
        defaultRepositories()
    }
}

val koogVersion = "0.4.0"

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        // Add plugin dependencies for compilation here:
        bundledPlugin("org.jetbrains.kotlin")
    }

    implementation("ai.koog:agents-core:$koogVersion") {
        excludeCoroutines()
    }
    implementation("ai.koog:agents-tools:$koogVersion") {
        excludeCoroutines()
    }
    implementation("ai.koog:agents-ext:$koogVersion") {
        excludeCoroutines()
    }
    implementation("ai.koog:agents-features-event-handler:$koogVersion") {
        excludeCoroutines()
    }
    implementation("ai.koog:prompt-executor-llms:$koogVersion") {
        excludeCoroutines()
    }
    implementation("ai.koog:prompt-executor-openai-client:$koogVersion") {
        excludeCoroutines()
    }
    implementation("ai.koog:prompt-executor-anthropic-client:$koogVersion") {
        excludeCoroutines()
    }
    implementation("ai.koog:prompt-executor-ollama-client:$koogVersion") {
        excludeCoroutines()
    }
    implementation("ai.grazie.client:client-ktor-jvm:0.4.65") {
        excludeCoroutines()
    }
    implementation("ai.grazie.api:api-gateway-client-jvm:0.4.65") {
        excludeCoroutines()
    }
    implementation("ai.jetbrains.code.prompt:code-prompt-executor-grazie-koog-jvm:1.0.0-beta.140") {
        excludeCoroutines()
    }


    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
