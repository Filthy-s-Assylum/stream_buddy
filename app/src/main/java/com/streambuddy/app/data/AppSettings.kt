/**
 * Centralized secrets management.
 *
 * Reads API keys from:
 *   1. `.env` file at project root (local development, git-ignored)
 *   2. `BuildConfig` constants (CI, provided as GitHub Actions secrets)
 *
 * This ensures keys are never committed to version control while remaining
 * consistently available across all build variants.
 *
 * Usage: AppSettings.tmdbApiKeySource / AppSettings.motnApiKeySource
 * These source values are used internally; the actual display/editing keys
 * remain tmdbApiKey / motnApiKey (read/write from the encrypted settings blob).
 */
object Secrets {
    /** TMDB API key read from env or CI. Never empty in CI; empty locally until set. */
    const val tmdb: String = systemProp("TMDB_API_KEY")
        .orElse(System.getenv("TMDB_API_KEY"))
        .orElse("")

    /** Movie of the Night API key read from env or CI. */
    const val motn: String = systemProp("MOTN_API_KEY")
        .orElse(System.getenv("MOTN_API_KEY"))
        .orElse("")
}

/**
 * Read all project `.env` values. .env is git-ignored and must never be committed.
 * Real environment variables win, which lets CI pass keys from GitHub secrets without writing a file.
 */
fun readDotEnv(): Map<String, String> {
    val file = File(rootProject.file(".env"))
    if (!file.isFile) return emptyMap()
    return file.readLines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
        val index = trimmed.indexOf('=')
        if (index <= 0) null
        else trimmed.substring(0, index).trim() to trimmed.substring(index + 1).trim().trim('"', '\'')
    }.toMap()
}