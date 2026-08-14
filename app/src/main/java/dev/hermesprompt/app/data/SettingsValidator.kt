package dev.hermesprompt.app.data

/**
 * Pure URL normalization and validation for the Hermes server address.
 *
 * Rules (from the build brief):
 *   1. Trim whitespace.
 *   2. If no scheme is present, prepend "https://".
 *   3. Strip any trailing slashes.
 *   4. Strip any path component (keep only scheme + authority).
 *   5. Must start with "http://" or "https://".
 *   6. Must have a non-empty host.
 *
 * This object has no Android dependencies and is fully unit-testable.
 */
object SettingsValidator {

    sealed class UrlResult {
        /** The normalized, valid URL. */
        data class Valid(val url: String) : UrlResult()
        /** Human-readable error message. */
        data class Invalid(val reason: String) : UrlResult()
    }

    /**
     * Normalizes and validates [raw] as a Hermes server URL.
     *
     * @param raw The raw string entered by the user.
     * @return [UrlResult.Valid] with the normalized URL, or [UrlResult.Invalid].
     */
    fun normalize(raw: String): UrlResult {
        var url = raw.trim()

        if (url.isEmpty()) {
            return UrlResult.Invalid("Server address cannot be empty.")
        }

        // Prepend https:// if no scheme given
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        // Validate scheme
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return UrlResult.Invalid("URL must start with http:// or https://.")
        }

        // Extract scheme and authority — strip path, query, fragment
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) {
            return UrlResult.Invalid("Invalid URL format.")
        }
        val afterScheme = url.substring(schemeEnd + 3)

        // Find where the authority ends (first '/', '?', or '#')
        val pathStart = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (pathStart >= 0) afterScheme.substring(0, pathStart) else afterScheme

        if (authority.isBlank()) {
            return UrlResult.Invalid("URL must include a host name or IP address.")
        }

        val scheme = url.substring(0, schemeEnd)
        val normalized = "$scheme://$authority"

        return UrlResult.Valid(normalized)
    }

    /**
     * Returns true if [url] is a valid, normalized URL (passes [normalize]).
     */
    fun isValid(url: String): Boolean = normalize(url) is UrlResult.Valid
}
