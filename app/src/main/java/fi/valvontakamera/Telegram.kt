package fi.valvontakamera

import android.content.Context
import android.util.Log
import org.json.JSONArray
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

    /** Jonotiedoston muoto: tyyppi \n polku \n lisätieto \n kuvateksti */
    private fun enqueue(c: Context, kind: String, path: String, extra: String, caption: String) {
        if (!enabled(c)) return
        val dir = outbox(c)
        val name = synchronized(lock) { "%d_%04d".format(System.currentTimeMillis(), seq++ % 10000) }
        val tmp = File(dir, "$name.tmp")
        tmp.writeText("$kind\n$path\n${extra.replace("\n", " ")}\n$caption")
        tmp.renameTo(File(dir, "$name.job"))
        kick(c)
    }

    /** Kuva, jonka alla painike (esim. "Lataa video"); painalluksesta tulee [Callback] jonka data = [buttonData]. */
    fun photo(c: Context, f: File, caption: String, buttonText: String? = null, buttonData: String? = null) {
        val markup = if (buttonText != null && buttonData != null) {
            JSONObject().put("inline_keyboard",
                JSONArray().put(JSONArray().put(JSONObject().put("text", buttonText).put("callback_data", buttonData))))
                .toString()
        } else ""
        enqueue(c, "p", f.absolutePath, markup, caption)
    }

    /** Video poistetaan lähetyksen jälkeen. [replyTo] = viesti, johon video liitetään vastauksena. */
    fun video(c: Context, f: File, caption: String, replyTo: Long = 0) =
        enqueue(c, "v", f.absolutePath, if (replyTo > 0) replyTo.toString() else "", caption)

    fun text(c: Context, msg: String) = enqueue(c, "t", "", "", msg)

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
            if (res == RETRY_NOW) continue
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
    private const val RETRY_NOW = 3

    private fun send(c: Context, job: File): Int {
        if (!enabled(c)) return DROP
        val lines = job.readText().split("\n", limit = 4)
        if (lines.size < 4) return DROP
        val kind = lines[0]; val path = lines[1]; val extra = lines[2]; val caption = lines[3]
        val token = Prefs.tgToken(c)
        val chat = Prefs.tgChat(c)
        val (code, body) = when (kind) {
            "t" -> post(token, "sendMessage", mapOf("chat_id" to chat, "text" to caption), null, null, null)
            "p" -> {
                val f = File(path); if (!f.exists()) return DROP
                val fields = linkedMapOf("chat_id" to chat, "caption" to caption)
                if (extra.isNotEmpty()) fields["reply_markup"] = extra
                post(token, "sendPhoto", fields, "photo", f, "image/jpeg")
            }
            "v" -> {
                val f = File(path); if (!f.exists()) return DROP
                val fields = linkedMapOf("chat_id" to chat, "caption" to caption, "supports_streaming" to "true")
                if (extra.isNotEmpty()) {
                    fields["reply_to_message_id"] = extra
                    fields["allow_sending_without_reply"] = "true"
                }
                post(token, "sendVideo", fields, "video", f, "video/mp4")
            }
            else -> return DROP
        }
        val migrated = try {
            JSONObject(body).optJSONObject("parameters")?.opt("migrate_to_chat_id")?.toString()
        } catch (_: Exception) { null }
        if (migrated != null) { Prefs.setTgChat(c, migrated); return RETRY_NOW }
        return when {
            code in 200..299 -> { lastOk = System.currentTimeMillis(); lastError = ""; OK }
            code == 429 || code >= 500 -> { lastError = "HTTP $code"; RETRY }
            else -> { lastError = "HTTP $code: " + desc(body).take(200); DROP }
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

    /** Hakee botin näkemät keskustelut ja ryhmät: lista (id, nimi). Kutsu taustasäikeessä. */
    fun findChats(token: String): List<Pair<String, String>> {
        if (polling) {
            // Valvonta on käynnissä ja lukee botin viestit itse: käytetään sen keräämiä tietoja
            val list = synchronized(seenChats) { seenChats.entries.filter { it.value != null }.map { it.key to it.value!! } }
            if (list.isEmpty()) throw Exception("Botti ei ole nähnyt viestejä. Kirjoita ryhmään /start ja yritä uudelleen.")
            return list.reversed().sortedBy { if (it.first.startsWith("-")) 0 else 1 }
        }
        val conn = URL("https://api.telegram.org/bot${token.trim()}/getUpdates?limit=100").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 20_000
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code != 200) throw Exception("HTTP $code (tarkista token)")
        val arr = JSONObject(body).getJSONArray("result")
        val found = LinkedHashMap<String, String>()
        val gone = HashSet<String>()
        for (i in arr.length() - 1 downTo 0) {
            val u = arr.getJSONObject(i)
            for (key in listOf("message", "edited_message", "channel_post", "my_chat_member", "chat_member")) {
                val o = u.optJSONObject(key) ?: continue
                val chat = o.optJSONObject("chat") ?: continue
                val id = chat.get("id").toString()
                if (o.has("migrate_to_chat_id")) {   // ryhmä muuttui superryhmäksi: uusi numero
                    val nid = o.get("migrate_to_chat_id").toString()
                    if (!found.containsKey(nid)) found[nid] = "Ryhmä: " + chat.optString("title")
                    gone.add(id)
                }
                if (found.containsKey(id) || gone.contains(id)) continue
                if (key == "my_chat_member") {
                    val st = o.optJSONObject("new_chat_member")?.optString("status") ?: ""
                    if (st == "left" || st == "kicked") { gone.add(id); continue }
                }
                val type = chat.optString("type")
                val name = when (type) {
                    "private" -> "Yksityinen: " + listOf(chat.optString("first_name"), chat.optString("last_name"))
                        .filter { it.isNotEmpty() }.joinToString(" ")
                    "group", "supergroup" -> "Ryhmä: " + chat.optString("title")
                    "channel" -> "Kanava: " + chat.optString("title")
                    else -> type
                }
                found[id] = name
            }
        }
        if (found.isEmpty()) throw Exception("Botti ei ole nähnyt viestejä. Kirjoita ryhmään /start ja yritä uudelleen.")
        // ryhmät ensin
        return found.entries.map { it.key to it.value }.sortedBy { if (it.first.startsWith("-")) 0 else 1 }
    }

    private fun get(token: String, method: String, query: String, readTimeout: Int = 20_000): Pair<Int, String> {
        val conn = URL("https://api.telegram.org/bot${token.trim()}/$method?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = readTimeout
        val code = conn.responseCode
        val body = try {
            (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) { "" }
        conn.disconnect()
        return code to body
    }

    private fun desc(body: String): String = try { JSONObject(body).optString("description") } catch (_: Exception) { body }

    /** Telegramin virheen selitys suomeksi. */
    fun explain(description: String): String {
        val d = description.lowercase()
        return when {
            "not a member" in d -> "Botti ei ole ryhmän jäsen. Lisää botti ryhmään (ryhmän nimi → Lisää jäsen)."
            "was kicked" in d -> "Botti on poistettu ryhmästä. Lisää se takaisin ryhmään ja hae chat ID uudelleen."
            "initiate conversation" in d -> "Valittu chat ID on yksityiskeskustelu, jota ei ole aloitettu. Avaa botti Telegramissa ja paina START – tai valitse ryhmä."
            "chat was deleted" in d || "deactivated" in d -> "Ryhmä on poistettu tai muuttunut. Lähetä ryhmään /start ja hae chat ID uudelleen."
            "upgraded to a supergroup" in d -> "Ryhmän numero on vaihtunut. Lähetä ryhmään /start ja hae chat ID uudelleen (valitse -100 alkava)."
            "not enough rights" in d || "have no rights" in d -> "Botilla ei ole oikeutta kirjoittaa ryhmään. Tee botista ryhmän ylläpitäjä."
            "chat not found" in d -> "Chat ID on väärä. Tyhjennä kenttä ja paina Hae chat ID."
            "unauthorized" in d || "not found" == d.trim() -> "Token on väärä. Kopioi token uudelleen BotFatherilta."
            else -> ""
        }
    }

    /** Lähettää testiviestin heti. Palauttaa null jos onnistui, muuten virheselityksen. Kutsu taustasäikeessä. */
    fun sendNow(token: String, chat: String, text: String): String? {
        val q = "chat_id=" + URLEncoder.encode(chat, "UTF-8") + "&text=" + URLEncoder.encode(text, "UTF-8")
        val (code, body) = get(token, "sendMessage", q)
        if (code == 200) return null
        // Superryhmäksi muuttunut ryhmä: kerrotaan uusi numero
        val newId = try {
            JSONObject(body).optJSONObject("parameters")?.opt("migrate_to_chat_id")?.toString()
        } catch (_: Exception) { null }
        if (newId != null) return "MIGRATE:$newId"
        val d = desc(body)
        val sb = StringBuilder("HTTP $code\n$d")
        val fi = explain(d)
        if (fi.isNotEmpty()) sb.append("\n\n").append(fi)
        // Lisädiagnoosi: onko botti ryhmässä?
        try {
            val (c1, me) = get(token, "getMe", "")
            if (c1 == 200) {
                val r = JSONObject(me).getJSONObject("result")
                val botId = r.get("id").toString()
                sb.append("\n\nBotti: @").append(r.optString("username"))
                val (c2, m) = get(token, "getChatMember",
                    "chat_id=" + URLEncoder.encode(chat, "UTF-8") + "&user_id=$botId")
                if (c2 == 200) {
                    val st = JSONObject(m).getJSONObject("result").optString("status")
                    sb.append("\nBotin tila ryhmässä: ").append(when (st) {
                        "administrator" -> "ylläpitäjä"
                        "member" -> "jäsen"
                        "restricted" -> "rajoitettu (ei saa kirjoittaa) – tee botista ylläpitäjä"
                        "left" -> "ei jäsen – lisää botti ryhmään"
                        "kicked" -> "poistettu/estetty – poista esto ja lisää botti ryhmään"
                        else -> st
                    })
                } else {
                    sb.append("\nBotti ei pääse ryhmään: ").append(desc(m))
                }
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    // ------------------------------------------------------------------ painikkeiden kuuntelu

    class Callback(val id: String, val data: String, val chatId: String, val msgId: Long)

    /** Ryhmästä tullut komento, esim. /kuva tai /video 20. */
    class Command(val cmd: String, val arg: String, val chatId: String, val msgId: Long, val from: String)

    @Volatile private var polling = false
    private var pollThread: Thread? = null
    /** Botin näkemät keskustelut: id -> nimi (null = botti ei enää jäsen). */
    private val seenChats = LinkedHashMap<String, String?>()

    private fun remember(u: JSONObject) {
        for (key in listOf("message", "edited_message", "channel_post", "my_chat_member", "chat_member", "callback_query")) {
            var o = u.optJSONObject(key) ?: continue
            if (key == "callback_query") o = o.optJSONObject("message") ?: continue
            val chat = o.optJSONObject("chat") ?: continue
            val id = chat.get("id").toString()
            val name = when (chat.optString("type")) {
                "private" -> "Yksityinen: " + chat.optString("first_name")
                "group", "supergroup" -> "Ryhmä: " + chat.optString("title")
                "channel" -> "Kanava: " + chat.optString("title")
                else -> chat.optString("type")
            }
            synchronized(seenChats) {
                if (o.has("migrate_to_chat_id")) {
                    seenChats[id] = null
                    seenChats[o.get("migrate_to_chat_id").toString()] = name
                    return
                }
                val st = if (key == "my_chat_member") o.optJSONObject("new_chat_member")?.optString("status") ?: "" else ""
                seenChats.remove(id)
                seenChats[id] = if (st == "left" || st == "kicked") null else name
            }
        }
    }

    /** Kuuntelee botille tulevia painalluksia (long polling). */
    fun startPolling(c: Context, onCallback: (Callback) -> Unit, onCommand: (Command) -> Unit) {
        val app = c.applicationContext
        synchronized(lock) {
            if (polling) return
            polling = true
            pollThread = Thread({ pollLoop(app, onCallback, onCommand) }, "telegram-poll").apply { isDaemon = true; start() }
        }
    }

    private fun registerCommands(token: String) {
        val cmds = JSONArray()
            .put(JSONObject().put("command", "kuva").put("description", "Kuva pihalta juuri nyt"))
            .put(JSONObject().put("command", "video").put("description", "Video tästä hetkestä (oletus 10 s, esim. /video 30)"))
            .put(JSONObject().put("command", "tila").put("description", "Kameran tila"))
            .put(JSONObject().put("command", "valo").put("description", "Puhelimen valo päälle/pois"))
        try { get(token, "setMyCommands", "commands=" + URLEncoder.encode(cmds.toString(), "UTF-8")) } catch (_: Exception) {}
    }

    fun stopPolling() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun pollLoop(c: Context, onCallback: (Callback) -> Unit, onCommand: (Command) -> Unit) {
        var offset = 0L
        var first = true
        while (polling) {
            try {
                val token = Prefs.tgToken(c)
                if (token.isEmpty() || !Prefs.tgEnabled(c)) { Thread.sleep(15_000); continue }
                // Ensimmäisellä kerralla ohitetaan vanhat painallukset
                if (first) registerCommands(token)
                val q = if (first) "offset=-1&timeout=0" else "offset=$offset&timeout=25"
                val (code, body) = get(token, "getUpdates", q, 40_000)
                if (code != 200) { Thread.sleep(if (code == 409) 30_000 else 10_000); continue }
                val arr = JSONObject(body).getJSONArray("result")
                for (i in 0 until arr.length()) {
                    val u = arr.getJSONObject(i)
                    offset = maxOf(offset, u.getLong("update_id") + 1)
                    remember(u)
                    if (first) continue
                    val m = u.optJSONObject("message")
                    val txt = m?.optString("text") ?: ""
                    if (m != null && txt.startsWith("/")) {
                        val chatId = m.optJSONObject("chat")?.get("id")?.toString() ?: ""
                        // Komennot hyväksytään vain asetetusta ryhmästä/keskustelusta
                        if (chatId == Prefs.tgChat(c)) {
                            val head = txt.trim().substringBefore(' ').removePrefix("/").substringBefore('@').lowercase()
                            val arg = txt.trim().substringAfter(' ', "").trim()
                            val from = m.optJSONObject("from")?.optString("first_name") ?: ""
                            onCommand(Command(head, arg, chatId, m.optLong("message_id"), from))
                        }
                        continue
                    }
                    val cb = u.optJSONObject("callback_query") ?: continue
                    val cbId = cb.optString("id")
                    val msg = cb.optJSONObject("message")
                    val data = cb.optString("data")
                    try {
                        get(token, "answerCallbackQuery", "callback_query_id=" + URLEncoder.encode(cbId, "UTF-8") +
                            "&text=" + URLEncoder.encode("Video tulossa ryhmään hetken päästä…", "UTF-8"))
                    } catch (_: Exception) {}
                    if (msg != null) {
                        onCallback(Callback(cbId, data,
                            msg.optJSONObject("chat")?.get("id")?.toString() ?: "", msg.optLong("message_id")))
                    }
                }
                first = false
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "poll", e)
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { return }
            }
        }
    }
}
