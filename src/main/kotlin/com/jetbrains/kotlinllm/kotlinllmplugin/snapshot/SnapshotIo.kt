package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.kotlinllm.kotlinllmplugin.services.ensureConfiguredKotlinLlmFolder
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Reads and writes [ScenarioSnapshot]s under the snapshot convention root
 * (`<generatedFolder>/snapshots/<scenario-id>/`).
 *
 * JSON serialization is intentionally hand-rolled (no kotlinx-serialization dep):
 * the snapshot state is a simple map/list-of-strings shape, and this keeps the
 * plugin build free of an extra serialization plugin.
 */
object SnapshotIo {

    fun scenarioDir(project: Project, scenarioId: String): Path? {
        val root = ensureConfiguredKotlinLlmFolder(project) ?: return null
        return Path.of(root.path, SNAPSHOT_ROOT, sanitizeScenarioId(scenarioId))
    }

    /** Materialize a scenario to disk (all files). */
    fun write(project: Project, snapshot: ScenarioSnapshot) {
        val dir = scenarioDir(project, snapshot.state.scenarioId) ?: return
        runCatching { dir.createDirectories() }
        writeTextFile(dir, SnapshotFiles.SNAPSHOT_JSON, encodeState(snapshot.state))
        writeTextFile(dir, SnapshotFiles.SPEC, snapshot.spec)
        writeTextFile(dir, SnapshotFiles.REVIEW, snapshot.review)
        writeTextFile(dir, SnapshotFiles.TESTS, snapshot.tests)
        writeTextFile(dir, SnapshotFiles.COVERAGE, snapshot.coverage)
        snapshot.dirty = false
        refreshVfs(project, dir)
    }

    /** Load a scenario from disk, or null if the JSON is missing. */
    fun read(project: Project, scenarioId: String): ScenarioSnapshot? {
        val dir = scenarioDir(project, scenarioId) ?: return null
        val json = readTextFile(dir, SnapshotFiles.SNAPSHOT_JSON) ?: return null
        val state = decodeState(json) ?: return null
        return ScenarioSnapshot(
            state = state,
            spec = readTextFile(dir, SnapshotFiles.SPEC).orEmpty(),
            review = readTextFile(dir, SnapshotFiles.REVIEW).orEmpty(),
            tests = readTextFile(dir, SnapshotFiles.TESTS).orEmpty(),
            coverage = readTextFile(dir, SnapshotFiles.COVERAGE).orEmpty(),
            dirty = false,
        )
    }

    /** List scenario ids currently materialized. */
    fun listScenarioIds(project: Project): List<String> {
        val root = ensureConfiguredKotlinLlmFolder(project) ?: return emptyList()
        val rootPath = Path.of(root.path, SNAPSHOT_ROOT)
        if (!rootPath.exists()) return emptyList()
        return runCatching {
            java.nio.file.Files.list(rootPath).use { stream ->
                stream.filter { it.toFile().isDirectory }.map { it.fileName.toString() }.toList().sorted()
            }
        }.getOrDefault(emptyList())
    }

    private fun writeTextFile(dir: Path, fileName: String, content: String) {
        val file = dir.resolve(fileName)
        runCatching { file.writeText(content) }
    }

    private fun readTextFile(dir: Path, fileName: String): String? {
        val file = dir.resolve(fileName)
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    private fun refreshVfs(project: Project, dir: Path) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)
        if (vf != null) {
            vf.refresh(false, true)
        } else {
            val parent = dir.parent
            if (parent != null) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(parent)?.refresh(false, true)
        }
    }

    private fun sanitizeScenarioId(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '_' || it == '-' || it == '.') it else '_' }.joinToString("")

    // ---- Minimal JSON encoding ----

    fun encodeState(state: SnapshotState): String = buildString {
        append('{')
        appendJsonField("scenarioId", state.scenarioId, comma = true)
        append("\"state\":{")
        state.state.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            appendJsonString(k); append(':'); appendJsonString(v)
        }
        append("},\"observedCalls\":[")
        state.observedCalls.forEachIndexed { i, call ->
            if (i > 0) append(',')
            append('{')
            appendJsonField("methodName", call.methodName, comma = true)
            append("\"arguments\":{")
            call.arguments.entries.forEachIndexed { j, (k, v) ->
                if (j > 0) append(',')
                appendJsonString(k); append(':'); appendJsonString(v)
            }
            append('}')
            append(",\"returnType\":")
            appendJsonString(call.returnType)
            call.returnValue?.let { append(",\"returnValue\":"); appendJsonString(it) }
            append('}')
        }
        append("],\"capturedAt\":")
        appendJsonString(state.capturedAt)
        append('}')
    }

    fun decodeState(json: String): SnapshotState? {
        val root = runCatching { JsonValue.parse(json) as? JsonValue.Object }.getOrNull() ?: return null
        val scenarioId = root.string("scenarioId") ?: return null
        val stateMap = (root.get("state") as? JsonValue.Object)?.entries
            ?.mapNotNull { (k, v) -> (v as? JsonValue.Str)?.let { k to it.value } }?.toMap()
            ?: emptyMap()
        val calls = (root.get("observedCalls") as? JsonValue.Array)?.items.orEmpty().mapNotNull { item ->
            val obj = item as? JsonValue.Object ?: return@mapNotNull null
            val methodName = obj.string("methodName") ?: return@mapNotNull null
            val args = (obj.get("arguments") as? JsonValue.Object)?.entries
                ?.mapNotNull { (k, v) -> (v as? JsonValue.Str)?.let { k to it.value } }?.toMap()
                ?: emptyMap()
            ObservedCall(
                methodName = methodName,
                arguments = args,
                returnType = obj.string("returnType").orEmpty(),
                returnValue = (obj.get("returnValue") as? JsonValue.Str)?.value,
            )
        }
        return SnapshotState(
            scenarioId = scenarioId,
            state = stateMap,
            observedCalls = calls,
            capturedAt = root.string("capturedAt").orEmpty(),
        )
    }

    private fun StringBuilder.appendJsonField(name: String, value: String, comma: Boolean) {
        appendJsonString(name); append(':'); appendJsonString(value); if (comma) append(',')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                else -> if (c.code < 0x20) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
        append('"')
    }

    // ---- Minimal JSON parser ----

    private sealed interface JsonValue {
        data class Object(val entries: LinkedHashMap<String, JsonValue>) : JsonValue {
            fun get(key: String): JsonValue? = entries[key]
            fun string(key: String): String? = (entries[key] as? Str)?.value
        }
        data class Array(val items: List<JsonValue>) : JsonValue
        data class Str(val value: String) : JsonValue
        data class Number(val value: String) : JsonValue
        data object Null : JsonValue
        data object True : JsonValue
        data object False : JsonValue

        companion object {
            private class Parser(val s: String) {
                var pos = 0
                fun parse(): JsonValue? {
                    skipWs()
                    if (pos >= s.length) return null
                    return parseValue()
                }
                private fun parseValue(): JsonValue {
                    skipWs()
                    return when (s[pos]) {
                        '{' -> parseObject()
                        '[' -> parseArray()
                        '"' -> Str(parseString())
                        't' -> { expect("true"); True }
                        'f' -> { expect("false"); False }
                        'n' -> { expect("null"); Null }
                        else -> Number(parseNumber())
                    }
                }
                private fun parseObject(): Object {
                    pos++ // {
                    val map = LinkedHashMap<String, JsonValue>()
                    skipWs()
                    if (pos < s.length && s[pos] == '}') { pos++; return Object(map) }
                    while (pos < s.length) {
                        skipWs()
                        val key = parseString()
                        skipWs()
                        expect(":")
                        map[key] = parseValue()
                        skipWs()
                        if (pos < s.length && s[pos] == ',') { pos++; continue }
                        if (pos < s.length && s[pos] == '}') { pos++; break }
                        break
                    }
                    return Object(map)
                }
                private fun parseArray(): Array {
                    pos++ // [
                    val items = mutableListOf<JsonValue>()
                    skipWs()
                    if (pos < s.length && s[pos] == ']') { pos++; return Array(items) }
                    while (pos < s.length) {
                        items.add(parseValue())
                        skipWs()
                        if (pos < s.length && s[pos] == ',') { pos++; continue }
                        if (pos < s.length && s[pos] == ']') { pos++; break }
                        break
                    }
                    return Array(items)
                }
                private fun parseString(): String {
                    if (s[pos] != '"') throw IllegalArgumentException("Expected string")
                    pos++
                    val sb = StringBuilder()
                    while (pos < s.length) {
                        val c = s[pos]
                        when {
                            c == '"' -> { pos++; return sb.toString() }
                            c == '\\' -> {
                                pos++
                                when (s[pos]) {
                                    '"' -> sb.append('"')
                                    '\\' -> sb.append('\\')
                                    '/' -> sb.append('/')
                                    'b' -> sb.append('\b')
                                    'f' -> sb.append('\u000C')
                                    'n' -> sb.append('\n')
                                    'r' -> sb.append('\r')
                                    't' -> sb.append('\t')
                                    'u' -> {
                                        val hex = s.substring(pos + 1, pos + 5)
                                        sb.append(hex.toInt(16).toChar())
                                        pos += 4
                                    }
                                    else -> sb.append(s[pos])
                                }
                                pos++
                            }
                            else -> { sb.append(c); pos++ }
                        }
                    }
                    return sb.toString()
                }
                private fun parseNumber(): String {
                    val start = pos
                    while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
                    return s.substring(start, pos)
                }
                private fun expect(token: String) {
                    if (s.startsWith(token, pos)) pos += token.length else throw IllegalArgumentException("Expected $token at $pos")
                }
                private fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
            }
            fun parse(json: String): JsonValue? = Parser(json).parse()
        }
    }
}
