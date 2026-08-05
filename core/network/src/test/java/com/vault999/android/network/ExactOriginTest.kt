package com.vault999.android.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExactOriginTest {
    @Test fun `route encodes values and stays on origin`() {
        val origin = ExactOrigin("https://juicewrldapi.com/juicewrld".toHttpUrl())
        assertEquals("https://juicewrldapi.com/juicewrld/songs/?searchall=A%2FB", origin.route("/juicewrld/songs/", mapOf("searchall" to "A/B")).toString())
    }

    @Test(expected = IllegalArgumentException::class) fun `rejects cross origin final URL`() {
        ExactOrigin("https://juicewrldapi.com".toHttpUrl()).validateFinal("https://evil.invalid/a".toHttpUrl())
    }

    @Test fun `same origin resolver rejects scheme-relative and credentials`() {
        val origin = ExactOrigin("https://juicewrldapi.com".toHttpUrl())
        assertEquals("https://juicewrldapi.com/juicewrld/a", origin.resolve("/juicewrld/a").toString())
        assertThrows(IllegalArgumentException::class.java) { origin.resolve("//juicewrldapi.com/other") }
        assertThrows(IllegalArgumentException::class.java) { origin.resolve("https://user:pass@juicewrldapi.com/a") }
    }
}
