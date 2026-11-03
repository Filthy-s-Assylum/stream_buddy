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
 * Usage: Secrets.tmdb / Secrets.motn
 * These source values are used internally; the actual display/editing keys
 * remain tmdbApiKey / motnApiKey (read/write from the encrypted settings blob).
 */
object Secrets {
    /** TMDB API key read from env or CI. Never empty in CI; empty locally until set. */
    val tmdb: String = com.streambuddy.app.BuildConfig.TMDB_API_KEY

    /** Movie of the Night API key read from env or CI. */
    val motn: String = com.streambuddy.app.BuildConfig.MOTN_API_KEY
}