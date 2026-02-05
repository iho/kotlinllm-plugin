import com.jetbrains.kotlinllm.mockLlm
import kotlin.test.Test
import kotlin.test.assertTrue

class SimpleTest {
    @Test
    fun loadsIssuesWithoutCallingGithubApi() {
        val fakeGithubService = mockLlm<GithubService>()
        val radar = GithubIssueRadar(fakeGithubService)
        val beginnerOnly = radar.loadIssues(
            "htt ps://github.com/example/beginner-only-issues"
        )
        println(beginnerOnly)
        val difficultOnly = radar.loadIssues(
            "https://github.com/example/difficult-issues-repo-only"
        )
        println(difficultOnly)
        assertTrue(beginnerOnly.isNotEmpty())
        assertTrue(beginnerOnly.all { it.isBeginnerFriendly })
        assertTrue(difficultOnly.isNotEmpty())
        assertTrue(difficultOnly.none { it.isBeginnerFriendly })
    }
}

fun main() {
    SimpleTest().loadsIssuesWithoutCallingGithubApi()
}