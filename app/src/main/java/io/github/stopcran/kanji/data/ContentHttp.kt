package io.github.stopcran.kanji.data

import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP GET response; closing it releases the connection. */
interface ContentResponse : Closeable {
    val code: Int
    val body: InputStream
    fun header(name: String): String?
}

/** The only network seam of [ContentSync]; tests substitute a fake. Implementations throw [java.io.IOException] when offline. */
fun interface ContentHttp {
    fun open(url: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): ContentResponse
}

object UrlConnectionHttp : ContentHttp {
    private const val USER_AGENT = "KanjiCards-Android"

    override fun open(url: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): ContentResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return try {
            val code = conn.responseCode
            object : ContentResponse {
                override val code = code
                override val body: InputStream get() = if (code in 200..299) conn.inputStream else conn.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))
                override fun header(name: String) = conn.getHeaderField(name)
                override fun close() = conn.disconnect()
            }
        } catch (e: java.io.IOException) {
            conn.disconnect()
            throw e
        }
    }
}