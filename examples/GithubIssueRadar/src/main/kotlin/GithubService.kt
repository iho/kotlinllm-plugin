import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

interface GithubService {
    fun fetchAllIssues(
        client: HttpClient,
        issuesApiUrl: String,
        progress: ProgressReporter = {}
    ): String
}

class GithubServiceImpl : GithubService {
    private val githubToken: String? = loadGithubTokenFromEnvFile()

    override fun fetchAllIssues(
        client: HttpClient,
        issuesApiUrl: String,
        progress: ProgressReporter
    ): String {
        val pages = mutableListOf<String>()
        var nextPageUrl: String? = openIssuesUrl(issuesApiUrl)
        var pageNumber = 1

        progress("Fetching GitHub issues from $issuesApiUrl")

        while (nextPageUrl != null) {
            progress("Fetching page $pageNumber")

            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(nextPageUrl))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GithubIssueRadar")
                .header("X-GitHub-Api-Version", "2022-11-28")
            if (!githubToken.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $githubToken")
            }
            val request = requestBuilder.GET().build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                error("GitHub API returned ${response.statusCode()}: ${response.body()}")
            }

            pages += unwrapJsonArray(response.body())
            nextPageUrl = extractNextPageUrl(response.headers().firstValue("Link").orElse(null))
            progress("Fetched page $pageNumber")
            pageNumber++
        }

        progress("Fetched ${pages.size} page(s) from GitHub")
        return "[${pages.filter { it.isNotBlank() }.joinToString(",")}]"
    }

    private fun unwrapJsonArray(arrayJson: String): String {
        val trimmed = arrayJson.trim()
        require(trimmed.startsWith("[") && trimmed.endsWith("]")) {
            "Expected a JSON array from GitHub API"
        }
        return trimmed.substring(1, trimmed.length - 1).trim()
    }

    private fun extractNextPageUrl(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        for (link in linkHeader.split(",")) {
            val parts = link.split(";").map { it.trim() }
            val url = parts.firstOrNull()
                ?.takeIf { it.startsWith("<") && it.endsWith(">") }
                ?.removePrefix("<")
                ?.removeSuffix(">")
            val isNext = parts.drop(1).any { it == "rel=\"next\"" }
            if (isNext) return url
        }
        return null
    }

    private fun openIssuesUrl(issuesApiUrl: String): String {
        return withQueryParameters(
            issuesApiUrl,
            mapOf(
                "state" to "open",
                "per_page" to "100"
            )
        )
    }

    private fun withQueryParameters(url: String, parameters: Map<String, String>): String {
        val withoutFragment = url.substringBefore("#")
        val fragment = url.substringAfter("#", missingDelimiterValue = "")
        val base = withoutFragment.substringBefore("?")
        val existingQuery = withoutFragment.substringAfter("?", missingDelimiterValue = "")
        val overriddenKeys = parameters.keys.map { it.lowercase() }.toSet()
        val retainedParameters = existingQuery
            .split("&")
            .filter { it.isNotBlank() }
            .filterNot { it.substringBefore("=").lowercase() in overriddenKeys }
        val query = (retainedParameters + parameters.map { "${it.key}=${it.value}" })
            .joinToString("&")
        val resolvedUrl = "$base?$query"
        return if (url.contains("#")) "$resolvedUrl#$fragment" else resolvedUrl
    }

    private fun loadGithubTokenFromEnvFile(): String? {
        val envFile = File(".env")
        if (!envFile.exists()) return null

        return envFile.readLines()
            .mapNotNull { parseEnvLine(it) }
            .firstOrNull { it.first == "GITHUB_TOKEN" }
            ?.second
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseEnvLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

        val withoutExport = trimmed.removePrefix("export ").trim()
        val equalsIndex = withoutExport.indexOf('=')
        if (equalsIndex <= 0) return null

        val key = withoutExport.substring(0, equalsIndex).trim()
        var value = withoutExport.substring(equalsIndex + 1).trim()

        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length - 1)
        }

        return key to value
    }
}
