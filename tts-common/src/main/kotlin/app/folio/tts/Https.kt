package app.folio.tts

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** HTTPS-only GET that follows up to 6 redirects by hand, refusing any that leave HTTPS. */
object Https {
    fun <T> open(address: String, consume: (InputStream, Long) -> T): T {
        var url = URL(address)
        repeat(6) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (url.protocol != "https") throw IOException("Not HTTPS: $url")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Folio-TTS Android")
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: throw IOException("Empty redirect"))
                } else {
                    if (code !in 200..299) throw IOException("HTTP $code for $url")
                    return connection.inputStream.use { consume(it, connection.contentLengthLong) }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }
}
