package io.github.stopcran.kanji.core.content

/** A GitHub content source. The canonical `owner/repo` (lower-case) identifies the corpus that review state belongs to. */
data class RepoSource(val owner: String, val repo: String, val branch: String) {
    val id: String get() = "${owner.lowercase()}/${repo.lowercase()}"

    /** Archive endpoint; branch names with slashes are allowed. */
    val zipUrl: String get() = "https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch"

    companion object {
        private val urlRegex = Regex("""^https://github\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100}?)(?:\.git)?/?$""")
        private val branchRegex = Regex("""^[A-Za-z0-9._][A-Za-z0-9._/-]{0,99}$""")

        fun parse(url: String, branch: String): RepoSource? {
            val m = urlRegex.matchEntire(url.trim()) ?: return null
            val b = branch.trim()
            if (!branchRegex.matches(b) || b.contains("..") || b.endsWith("/") || b.endsWith(".lock")) return null
            val repo = m.groupValues[2]
            if (repo == "." || repo == "..") return null
            return RepoSource(m.groupValues[1], repo, b)
        }
    }
}
