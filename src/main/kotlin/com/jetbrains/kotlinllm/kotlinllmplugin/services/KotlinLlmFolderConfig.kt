package com.jetbrains.kotlinllm.kotlinllmplugin.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents.AgentModelRef
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents.CustomAgentSpec
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
private const val AGENT_MODELS_KEY = "agentModels"
private const val CUSTOM_AGENTS_KEY = "customAgents"
private const val GENERATED_RELATIVE_DIR = "com/jetbrains/kotlinllm/generated"

data class KotlinLlmProjectConfig(
    val generatedFolder: String = "",
    val apiKey: String = "",
    val llmProvider: KotlinLlmProvider = KotlinLlmProvider.OpenAI,
    val buildsFolder: String = "",
    val ollamaBaseUrl: String = DEFAULT_OLLAMA_BASE_URL,
    val ollamaModel: String = DEFAULT_OLLAMA_MODEL,
    val anthropicModel: String = DEFAULT_ANTHROPIC_MODEL,
    /** Per-role-agent model overrides, keyed by role display name (e.g. "BugFixer"). */
    val agentModels: Map<String, String> = emptyMap(),
    /** User-defined custom agents to add to the orchestration loop. */
    val customAgents: List<CustomAgentSpec> = emptyList(),
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
        agentModels = parseAgentModels(entries[AGENT_MODELS_KEY]),
        customAgents = parseCustomAgents(entries[CUSTOM_AGENTS_KEY]),
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
    setOrRemove(entries, AGENT_MODELS_KEY, serializeAgentModels(config.agentModels))
    setOrRemove(entries, CUSTOM_AGENTS_KEY, serializeCustomAgents(config.customAgents))
    writeConfigEntries(configPath, entries)
}

/** Parse `RoleName=provider:modelId` pairs separated by `;`. */
private fun parseAgentModels(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val result = linkedMapOf<String, String>()
    raw.split(';').forEach { pair ->
        val idx = pair.indexOf('=')
        if (idx > 0) {
            val role = pair.substring(0, idx).trim()
            val model = pair.substring(idx + 1).trim()
            if (role.isNotEmpty() && model.isNotEmpty()) {
                result[role] = model
            }
        }
    }
    return result
}

private fun serializeAgentModels(models: Map<String, String>): String =
    models.entries.joinToString(";") { "${it.key}=${it.value}" }

/** Parse a JSON array of custom agent specs. */
private fun parseCustomAgents(raw: String?): List<CustomAgentSpec> {
    if (raw.isNullOrBlank()) return emptyList()
    val trimmed = raw.trim()
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
    val inner = trimmed.substring(1, trimmed.length - 1)
    if (inner.isBlank()) return emptyList()
    return splitJsonObjects(inner).mapNotNull { obj ->
        val name = jsonStringValue(obj, "name") ?: return@mapNotNull null
        val persona = jsonStringValue(obj, "persona") ?: return@mapNotNull null
        val model = jsonStringValue(obj, "model")?.let { AgentModelRef.parse(it) }
        val tools = jsonStringArray(obj, "tools")
        CustomAgentSpec(name = name, persona = persona, model = model, tools = tools)
    }
}

private fun serializeCustomAgents(agents: List<CustomAgentSpec>): String {
    if (agents.isEmpty()) return ""
    return agents.joinToString(",", prefix = "[", postfix = "]") { agent ->
        buildString {
            append("{\"name\":").append(jsonEscape(agent.name))
            append(",\"persona\":").append(jsonEscape(agent.persona))
            agent.model?.let { append(",\"model\":").append(jsonEscape(it.configKey)) }
            if (agent.tools.isNotEmpty()) {
                append(",\"tools\":[")
                append(agent.tools.joinToString(",") { jsonEscape(it) })
                append("]")
            }
            append("}")
        }
    }
}

/** Split a JSON array body into top-level `{...}` objects (no nested objects). */
private fun splitJsonObjects(body: String): List<String> {
    val result = mutableListOf<String>()
    var depth = 0
    var start = -1
    var inString = false
    var i = 0
    while (i < body.length) {
        val c = body[i]
        when {
            c == '"' -> inString = !inString
            !inString && c == '{' -> {
                if (depth == 0) start = i
                depth++
            }
            !inString && c == '}' -> {
                depth--
                if (depth == 0 && start >= 0) {
                    result.add(body.substring(start, i + 1))
                    start = -1
                }
            }
        }
        i++
    }
    return result
}

private fun jsonStringValue(obj: String, key: String): String? {
    val regex = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    return regex.find(obj)?.groupValues?.get(1)?.let { unescapeJson(it) }
}

private fun jsonStringArray(obj: String, key: String): List<String> {
    val regex = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]")
    val body = regex.find(obj)?.groupValues?.get(1) ?: return emptyList()
    return Regex("\"((?:\\\\.|[^\"\\\\])*)\"").findAll(body)
        .map { unescapeJson(it.groupValues[1]) }
        .toList()
}

private fun unescapeJson(s: String): String =
    s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")

private fun jsonEscape(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

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
    entries[AGENT_MODELS_KEY]?.let { orderedEntries[AGENT_MODELS_KEY] = it }
    entries[CUSTOM_AGENTS_KEY]?.let { orderedEntries[CUSTOM_AGENTS_KEY] = it }
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
