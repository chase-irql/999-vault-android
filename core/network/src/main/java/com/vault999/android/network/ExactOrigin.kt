package com.vault999.android.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class ExactOrigin(private val baseUrl: HttpUrl) {
    init {
        require(baseUrl.isHttps) { "Production origin must use HTTPS" }
        require(baseUrl.username.isEmpty() && baseUrl.password.isEmpty()) { "Credentials in origins are forbidden" }
        require(baseUrl.query == null && baseUrl.fragment == null) { "Origin must not include query or fragment" }
    }

    val origin: String = "${baseUrl.scheme}://${baseUrl.host}${if (baseUrl.port == 443) "" else ":${baseUrl.port}"}"

    fun route(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        require(path.startsWith('/') && !path.startsWith("//")) { "Route must be an absolute path" }
        val builder = origin.toHttpUrl().newBuilder().encodedPath(path)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    fun validateFinal(url: HttpUrl): HttpUrl {
        require(url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port) { "Cross-origin URL rejected" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "Credentials in URLs are forbidden" }
        return url
    }

    fun resolve(reference: String): HttpUrl {
        require(reference.isNotBlank() && reference.length <= 4_096 && !reference.startsWith("//")) {
            "Invalid same-origin URL"
        }
        val resolved = origin.toHttpUrl().resolve(reference) ?: throw IllegalArgumentException("Invalid same-origin URL")
        return validateFinal(resolved)
    }
}

object NetworkBounds {
    const val ARCHIVE_JSON_BYTES: Long = 8L * 1024 * 1024
    const val ACCOUNT_JSON_BYTES: Long = 2L * 1024 * 1024
    const val TEXT_VIEWER_BYTES: Long = 5L * 1024 * 1024
    const val ERROR_BODY_BYTES: Long = 64L * 1024
}
