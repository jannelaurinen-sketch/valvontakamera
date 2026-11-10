package fi.valvontakamera

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lähettää kuvat, videot ja viestit Telegram-botin kautta.
 * Lähetettävät asiat kirjoitetaan jonoon (filesDir/outbox, .job-tiedostot), joten mitään ei katoa
 * vaikka Wi-Fi katkeaisi hetkeksi: lähetystä yritetään uudelleen 30 s välein.
 */
object Telegram {
    private const val TAG = "Telegram"
    private val lock = Object()
    private var thread: Thread? = null
    private var seq = 0

    @Volatile var queueSize = 0; private set
    @Volatile var lastError = ""; private set
    @Volatile var lastOk = 0L; private set

    fun configured(c: Context) = Prefs.tgToken(c).isNotEmpty() && Prefs.tgChat(c).isNotEmpty()
    fun enabled(c: Context) = Prefs.tgEnabled(c) && configured(c)

    private fun outbox(c: Context) = File(c.filesDir, "outbox").apply { mkdirs() }

    private fun enqueue(c: Context, kind: String, path: String, caption: String) {
        if (!enabled(c)) return
        val dir = outbox(c)
        val name = synchronized(lock) { "%d_%04d".format(System.currentTimeMillis(), seq++ % 10000) }
        val tmp = File(dir, "$name.tmp")
        tmp.writeText("$kind\n$path\n$caption")
        tmp.renameTo(File(dir, "$name.job"))
        kick(c)
    }

    fun photo(c: Context, f: File, caption: String) = enqueue(c, "p", f.absolutePath, caption)
    /** Video poistetaan lähetyksen jälkeen. */
    fun video(c: Context, f: File, caption: String) = enqueue(c, "v", f.absolutePath, caption)
    fun text(c: Context, msg: String) = enqueue(c, "t", "", msg)

    fun kick(c: Context) {
        val app = c.applicationContext
        synchronized(lock) {
            if (thread?.isAlive == true) { lock.notifyAll(); return }
            thread = Thread({ loop(app) }, "telegram").apply { isDaemon = true; start() }
        }
    }

    private fun loop(c: Context) {
        while (true) {
            val jobs = outbox(c).listFiles { f -> f.name.endsWith(".job") }?.sortedBy { it.name } ?: emptyList()
            queueSize = jobs.size
            if (jobs.isEmpty()) {
                synchronized(lock) { lock.wait(60_000) }
                continue
            }
            val job = jobs.first()
            val res = try { send(c, job) } catch (e: Exception) {
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
                Log.w(TAG, "send", e)
                RETRY
            }
            if (res == RETRY) {
                synchronized(lock) { lock.wait(30_000) }
            } else {
                val lines = try { job.readLines() } catch (_: Exception) { emptyList() }
                if (lines.getOrNull(0) == "v") lines.getOrNull(1)?.let { File(it).delete() }
                job.delete()
            }
        }
    }

    private const val OK = 0
    private const val RETRY = 1
    private const val DROP = 2

    private fun send(c: Context, job: File): Int {
        if (!enabled(c)) return DROP
        val lines = job.readText().split("\n", limit = 3)
        if (lines.size < 3) return DROP
        val (kind, path, caption) = lines
        val token = Prefs.tgToken(c)
        val chat = Prefs.tgChat(c)
        val (code, body) = when (kind) {
            "t" -> post(token, "sendMessage", mapOf("chat_id" to chat, "text" to caption), null, null, null)
            "p" -> {
                val f = File(path); if (!f.exists()) return DROP
                post(token, "sendPhoto", mapOf("chat_id" to chat, "caption" to caption), "photo", f, "image/jpeg")
            }
            "v" -> {
                val f = File(path); if (!f.exists()) return DROP
                post(token, "sendVideo", mapOf("chat_id" to chat, "caption" to caption, "supports_streaming" to "true"),
                    "video", f, "video/mp4")
            }
            else -> return DROP
        }
        return when {
            code in 200..299 -> { lastOk = System.currentTimeMillis(); lastError = ""; OK }
            code == 429 || code >= 500 -> { lastError = "HTTP $code"; RETRY }
            else -> { lastError = "HTTP $code: ${body.take(200)}"; DROP }
        }
    }

    private fun post(token: String, method: String, fields: Map<String, String>,
                     fileField: String?, file: File?, mime: String?): Pair<Int, String> {
        val boundary = "----valvonta" + System.nanoTime()
        val conn = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 180_000
        conn.doOutput = true
        conn.requestMethod = "POST"
        conn.setChunkedStreamingMode(64 * 1024)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        DataOutputStream(conn.outputStream).use { out ->
            for ((k, v) in fields) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n".toByteArray())
                out.write(v.toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray())
            }
            if (file != null && fileField != null) {
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\n" +
                        "Content-Type: $mime\r\n\r\n").toByteArray())
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.write("\r\n".toByteArray())
            }
            out.write("--$boundary--\r\n".toByteArray())
        }
        val code = conn.responseCode
        val body = try {
            (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) { "" }
        conn.disconnect()
        return code to body
    }

    /** Hakee chat ID:n viimeisimmästä botille lähetetystä viestistä. Kutsu taustasäikeessä. */
    fun findChatId(token: String): String {
        val conn = URL("https://api.telegram.org/bot${token.trim()}/getUpdates").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 20_000
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code != 200) throw Exception("HTTP $code (tarkista token)")
        val arr = JSONObject(body).getJSONArray("result")
        for (i in arr.length() - 1 downTo 0) {
            val u = arr.getJSONObject(i)
            val msg = u.optJSONObject("message") ?: u.optJSONObject("channel_post") ?: continue
            return msg.getJSONObject("chat").get("id").toString()
        }
        throw Exception("Ei viestejä. Lähetä botille ensin jokin viesti.")
    }

    /** Lähettää testiviestin heti. Kutsu taustasäikeessä. */
    fun sendNow(token: String, chat: String, text: String) {
        val q = "chat_id=" + URLEncoder.encode(chat, "UTF-8") + "&text=" + URLEncoder.encode(text, "UTF-8")
        val conn = URL("https://api.telegram.org/bot${token.trim()}/sendMessage?$q").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 20_000
        val code = conn.responseCode
        if (code != 200) {
            val b = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("HTTP $code ${b.take(150)}")
        }
    }
}
