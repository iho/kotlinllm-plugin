import com.jetbrains.kotlinllm.asLlm

fun main() {
    val repoUrl = "https://github.com/jetbrains/kotlinconf-app"
    val radar = GithubIssueRadar()

    val issues = radar.loadIssues(repoUrl, progress = ::println)
    val beginnerFriendlyIssues = issues.filter { it.isBeginnerFriendly }

    if (beginnerFriendlyIssues.isEmpty()) {
        println("No beginner-friendly issues found.")
        return
    }

    println("Beginner-friendly issues:")
    beginnerFriendlyIssues.forEachIndexed { index, issue ->
        println("${index + 1}. ${issue.title}")
        println("   ${issue.url}")
    }
}
