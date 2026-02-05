data class Issue(
    val title: String,
    val url: String,
    val labelNames: List<String>,
    val isBeginnerFriendly: Boolean
)

data class ApiIssueInfo(
    val title: String,
    val url: String,
    val labelNames: List<String>,
)

data class JsonIssue(
    val title: String,
    val url: String,
    val labelNames: List<String>,
)
