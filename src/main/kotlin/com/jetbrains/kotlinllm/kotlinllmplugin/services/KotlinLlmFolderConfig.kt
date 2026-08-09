package com.jetbrains.kotlinllm.kotlinllmplugin.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val KOTLIN_LLM_CONFIG_FILE = ".kotlinllm"
private const val GENERATED_FOLDER_KEY = "generatedFolder"
private const val API_KEY_KEY = "apiKey"
private const val LLM_PROVIDER_KEY = "llmProvider"
private const val BUILDS_FOLDER_KEY = "buildsFolder"
private const val OLLAMA_BASE_URL_KEY = "ollamaBaseUrl"
private const val OLLAMA_MODEL_KEY = "ollamaModel"
private const val ANTHROPIC_MODEL_KEY = "anthropicModel"
private const val GENERATED_RELATIVE_DIR = "com/jetbrains/kotlinllm/generated"

data class KotlinLlmProjectConfig(
    val generatedFolder: String = "",
    val apiKey: String = "",
    val llmProvider: KotlinLlmProvider = KotlinLlmProvider.OpenAI,
    val buildsFolder: String = "",
    val ollamaBaseUrl: String = DEFAULT_OLLAMA_BASE_URL,
    val ollamaModel: String = DEFAULT_OLLAMA_MODEL,
    val anthropicModel: String = DEFAULT_ANTHROPIC_MODEL,
)

enum class KotlinLlmProvider(
    val configValue: String,
    private val displayName: String,
) {
    OpenAI("openai", "OpenAI"),
    Grazie("grazie", "Grazie"),
    Ollama("ollama", "Ollama (local)"),
    Anthropic("anthropic", "Anthropic Claude");

    override fun toString(): String = displayName

    companion object {
        fun fromConfigValue(value: String?): KotlinLlmProvider {
            return entries.firstOrNull { it.configValue.equals(value.orEmpty(), ignoreCase = true) }
                ?: OpenAI
        }
    }
}

const val DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434"
const val DEFAULT_OLLAMA_MODEL = "llama3.2:latest"
const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-5"

fun readKotlinLlmProjectConfig(project: Project): KotlinLlmProjectConfig {
    val projectBasePath = project.basePath ?: return KotlinLlmProjectConfig()
    val entries = readConfigEntries(projectBasePath)
    return KotlinLlmProjectConfig(
        generatedFolder = entries[GENERATED_FOLDER_KEY].orEmpty(),
        apiKey = entries[API_KEY_KEY].orEmpty(),
        llmProvider = KotlinLlmProvider.fromConfigValue(entries[LLM_PROVIDER_KEY]),
        buildsFolder = entries[BUILDS_FOLDER_KEY].orEmpty(),
        ollamaBaseUrl = entries[OLLAMA_BASE_URL_KEY]?.takeIf { it.isNotBlank() } ?: DEFAULT_OLLAMA_BASE_URL,
        ollamaModel = entries[OLLAMA_MODEL_KEY]?.takeIf { it.isNotBlank() } ?: DEFAULT_OLLAMA_MODEL,
        anthropicModel = entries[ANTHROPIC_MODEL_KEY]?.takeIf { it.isNotBlank() } ?: DEFAULT_ANTHROPIC_MODEL,
    )
}

fun saveKotlinLlmProjectConfig(project: Project, config: KotlinLlmProjectConfig) {
    val projectBasePath = project.basePath ?: return
    val configPath = Path.of(projectBasePath, KOTLIN_LLM_CONFIG_FILE)
    if (!Files.exists(configPath.parent)) {
        Files.createDirectories(configPath.parent)
    }

    val entries = readConfigEntries(projectBasePath).toMutableMap()
    setOrRemove(entries, GENERATED_FOLDER_KEY, config.generatedFolder)
    setOrRemove(entries, API_KEY_KEY, config.apiKey)
    setOrRemove(entries, LLM_PROVIDER_KEY, config.llmProvider.configValue)
    setOrRemove(entries, BUILDS_FOLDER_KEY, config.buildsFolder)
    setOrRemove(entries, OLLAMA_BASE_URL_KEY, config.ollamaBaseUrl)
    setOrRemove(entries, OLLAMA_MODEL_KEY, config.ollamaModel)
    setOrRemove(entries, ANTHROPIC_MODEL_KEY, config.anthropicModel)
    writeConfigEntries(configPath, entries)
}

fun resolveConfiguredKotlinLlmFolder(project: Project): VirtualFile? {
    val configuredFolderPath = resolveConfiguredKotlinLlmFolderPath(project) ?: return null
    return LocalFileSystem.getInstance().findFileByNioFile(configuredFolderPath)
}

fun resolveConfiguredKotlinLlmSourceRoot(project: Project): VirtualFile? {
    val projectBasePath = project.basePath ?: return null
    val configuredPath = readConfigEntries(projectBasePath)[GENERATED_FOLDER_KEY]
        ?: return null
    val sourceRoot = resolveConfiguredPath(projectBasePath, configuredPath)
        ?.normalize()
        ?.withoutGeneratedSuffix()
        ?: return null
    return LocalFileSystem.getInstance().findFileByNioFile(sourceRoot)
}

fun ensureConfiguredKotlinLlmFolder(project: Project): VirtualFile? {
    val configuredFolderPath = resolveConfiguredKotlinLlmFolderPath(project) ?: return null
    if (!Files.exists(configuredFolderPath)) {
        Files.createDirectories(configuredFolderPath)
    }
    return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(configuredFolderPath)
}

private fun resolveConfiguredKotlinLlmFolderPath(project: Project): Path? {
    val projectBasePath = project.basePath ?: return null
    val entries = readConfigEntries(projectBasePath)
    val configuredPath = entries[GENERATED_FOLDER_KEY]
        ?: return null

    val nioPath = resolveConfiguredPath(projectBasePath, configuredPath) ?: return null

    val normalized = nioPath.normalize()
    val generatedSuffix = Path.of(GENERATED_RELATIVE_DIR)
    return if (endsWithPath(normalized, generatedSuffix)) {
        normalized
    } else {
        normalized.resolve(generatedSuffix).normalize()
    }
}

fun saveConfiguredKotlinLlmFolder(project: Project, directory: VirtualFile) {
    val storedPath = toProjectRelativeKotlinLlmPath(project, Path.of(directory.path))
    val currentConfig = readKotlinLlmProjectConfig(project)
    saveKotlinLlmProjectConfig(project, currentConfig.copy(generatedFolder = storedPath))
}

fun readConfiguredKotlinLlmApiKey(project: Project): String? {
    return readKotlinLlmProjectConfig(project).apiKey.takeIf { it.isNotBlank() }
}

fun readConfiguredKotlinLlmProvider(project: Project): KotlinLlmProvider {
    return readKotlinLlmProjectConfig(project).llmProvider
}

fun resolveConfiguredBuildsFolderPath(project: Project): Path? {
    val projectBasePath = project.basePath ?: return null
    val configuredPath = readKotlinLlmProjectConfig(project).buildsFolder.takeIf { it.isNotBlank() }
        ?: return null
    return resolveConfiguredPath(projectBasePath, configuredPath)?.normalize()
}

fun toProjectRelativeKotlinLlmPath(project: Project, path: Path): String {
    val projectBasePath = project.basePath ?: return path.toString()
    val projectRoot = Path.of(projectBasePath)

    return runCatching {
        if (path.startsWith(projectRoot)) {
            projectRoot.relativize(path).toString()
        } else {
            path.toString()
        }
    }.getOrElse { path.toString() }
}

private fun endsWithPath(path: Path, suffix: Path): Boolean {
    val normalizedPath = path.normalize()
    val normalizedSuffix = suffix.normalize()
    if (normalizedPath.nameCount < normalizedSuffix.nameCount) return false
    return normalizedPath.endsWith(normalizedSuffix)
}

private fun Path.withoutGeneratedSuffix(): Path {
    val generatedSuffix = Path.of(GENERATED_RELATIVE_DIR)
    if (!endsWithPath(this, generatedSuffix)) return this
    var result = this
    repeat(generatedSuffix.nameCount) {
        result = result.parent ?: return this
    }
    return result
}

private fun resolveConfiguredPath(projectBasePath: String, configuredPath: String): Path? {
    if (configuredPath.isBlank()) return null
    return Path.of(configuredPath).let { path ->
        if (path.isAbsolute) path else Path.of(projectBasePath, configuredPath)
    }
}

private fun setOrRemove(entries: MutableMap<String, String>, key: String, value: String) {
    val normalized = value.trim()
    if (normalized.isBlank()) {
        entries.remove(key)
    } else {
        entries[key] = normalized
    }
}

private fun readConfigEntries(projectBasePath: String): Map<String, String> {
    val configPath = Path.of(projectBasePath, KOTLIN_LLM_CONFIG_FILE)
    if (!configPath.exists()) return emptyMap()

    val raw = runCatching { configPath.readText() }.getOrNull()?.trim().orEmpty()
    if (raw.isEmpty()) return emptyMap()

    val entries = linkedMapOf<String, String>()
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .forEach { line ->
            val splitIndex = line.indexOf('=')
            if (splitIndex >= 0) {
                val key = line.substring(0, splitIndex).trim()
                val value = line.substring(splitIndex + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    entries[key] = value
                }
            } else {
                // Backward compatibility: single-line path means generated folder.
                entries.putIfAbsent(GENERATED_FOLDER_KEY, line)
            }
        }

    return entries
}

private fun writeConfigEntries(configPath: Path, entries: Map<String, String>) {
    val orderedEntries = linkedMapOf<String, String>()
    entries[GENERATED_FOLDER_KEY]?.let { orderedEntries[GENERATED_FOLDER_KEY] = it }
    entries[API_KEY_KEY]?.let { orderedEntries[API_KEY_KEY] = it }
    entries[LLM_PROVIDER_KEY]?.let { orderedEntries[LLM_PROVIDER_KEY] = it }
    entries[BUILDS_FOLDER_KEY]?.let { orderedEntries[BUILDS_FOLDER_KEY] = it }
    entries[OLLAMA_BASE_URL_KEY]?.let { orderedEntries[OLLAMA_BASE_URL_KEY] = it }
    entries[OLLAMA_MODEL_KEY]?.let { orderedEntries[OLLAMA_MODEL_KEY] = it }
    entries[ANTHROPIC_MODEL_KEY]?.let { orderedEntries[ANTHROPIC_MODEL_KEY] = it }
    entries.forEach { (key, value) ->
        if (!orderedEntries.containsKey(key)) {
            orderedEntries[key] = value
        }
    }

    val text = buildString {
        orderedEntries.forEach { (key, value) ->
            append(key)
            append("=")
            append(value)
            append("\n")
        }
    }
    configPath.writeText(text)
}
