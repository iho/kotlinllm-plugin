import com.jetbrains.kotlinllm.asLlm
import java.net.URI
import java.net.http.HttpClient

class GithubIssueRadar(
    private val githubService: GithubService = GithubServiceImpl(),
    private val client: HttpClient = newGithubHttpClient()
) {
    fun loadIssues(
        repoUrl: String,
        progress: ProgressReporter = {}
    ): List<Issue> {
        progress("Resolving repository issues URL")
        return detectIssuesFromResponse(fetchIssuesJsonForRepo(repoUrl, progress), progress)
    }

    fun fetchIssuesJsonForRepo(
        repoUrl: String,
        progress: ProgressReporter = {}
    ): String {
        val issuesApiUrl = githubIssuesApiUrl(repoUrl)
        return githubService.fetchAllIssues(client, issuesApiUrl, progress)
    }

    fun githubIssuesApiUrl(repoUrl: String): String {
        return asLlm(repoUrl, "API endpoint for fetching issues")
    }

    fun detectIssuesFromResponse(
        response: String,
        progress: ProgressReporter = {}
    ): List<Issue> {
        progress("Parsing GitHub API response")
        val apiIssues = parseApiIssues(response)
        progress("Found ${apiIssues.size} issue(s) in the GitHub response")
        progress("Asking the LLM to split issues into candidates")

        val parsed = asLlm<String, List<Issue>>(response).filter { it.isBeginnerFriendly }

        val rawIssues: List<String> = asLlm<String, List<String>>(response, "split into list of json objects representing issues")
        progress("LLM returned ${rawIssues.size} issue candidate(s)")

        val issues = mutableListOf<Issue>()
        rawIssues.forEachIndexed { index, rawJson ->
            val jsonIssue: JsonIssue = asLlm(rawJson)
            progress("Analyzing issue ${index + 1}/${rawIssues.size}: ${jsonIssue.title}")

            val isBeginnerFriendly = jsonIssue.labelNames.anyIndexed { labelIndex, label ->
                progress("  Checking label ${labelIndex + 1}/${jsonIssue.labelNames.size} for ${jsonIssue.title}")
                asLlm<String, Boolean>(label, hint = "check if the label is beginner friendly")
            }


            issues += with(jsonIssue) {
                Issue(
                    title = jsonIssue.title,
                    url = jsonIssue.url,
                    labelNames = jsonIssue.labelNames,
                    isBeginnerFriendly = isBeginnerFriendly
                )
            }
        }

        if (apiIssues.isEmpty()) return issues

        val predictedIssues = issues.filter { it.isBeginnerFriendly }
        val predictedUrls = predictedIssues
            .mapNotNull { canonicalIssueUrl(it.url) }
            .toSet()
        val apiTitleCounts = apiIssues
            .map { normalizeIssueTitle(it.title) }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
        val predictedTitles = predictedIssues
            .map { normalizeIssueTitle(it.title) }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it == 1 }
            .keys
            .filter { apiTitleCounts[it] == 1 }
            .toSet()

        progress("Reconciling LLM results with GitHub API labels")
        return apiIssues.map { issue ->
            val isBeginnerFriendly =
                canonicalIssueUrl(issue.url) in predictedUrls || normalizeIssueTitle(issue.title) in predictedTitles
            Issue(
                title = issue.title,
                url = issue.url,
                labelNames = issue.labelNames,
                isBeginnerFriendly = isBeginnerFriendly
            )
        }
    }
}

fun detectIssuesForRepo(
    repoUrl: String,
    client: HttpClient = newGithubHttpClient(),
    githubService: GithubService = GithubServiceImpl(),
    progress: ProgressReporter = {}
): List<Issue> {
    return GithubIssueRadar(githubService, client).loadIssues(repoUrl, progress)
}

fun fetchIssuesJsonForRepo(
    repoUrl: String,
    client: HttpClient = newGithubHttpClient(),
    githubService: GithubService = GithubServiceImpl(),
    progress: ProgressReporter = {}
): String {
    return GithubIssueRadar(githubService, client).fetchIssuesJsonForRepo(repoUrl, progress)
}

fun githubIssuesApiUrl(repoUrl: String): String {
    return GithubIssueRadar().githubIssuesApiUrl(repoUrl)
}

fun detectIssuesFromResponse(
    response: String,
    progress: ProgressReporter = {}
): List<Issue> {
    return GithubIssueRadar().detectIssuesFromResponse(response, progress)
}

fun newGithubHttpClient(): HttpClient {
    return HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}

private const val DETECT_EASY_ISSUES_HINT = """
Return the list of all beginner friendly issues for provided repository.
Unique approach for each repository.
Beginner friendly labelNames will differ.
"""

private fun canonicalIssueUrl(url: String): String? {
    val trimmed = url.trim().removeSuffix("/")
    if (trimmed.isEmpty()) return null
    return trimmed
        .replace("https://api.github.com/repos/", "https://github.com/")
        .replace("/issues/", "/issues/")
        .lowercase()
}

private fun normalizeIssueTitle(title: String): String {
    return title.trim().replace("\\s+".toRegex(), " ").lowercase()
}

private inline fun <T> Iterable<T>.anyIndexed(predicate: (index: Int, T) -> Boolean): Boolean {
    var index = 0
    for (item in this) {
        if (predicate(index, item)) return true
        index++
    }
    return false
}
