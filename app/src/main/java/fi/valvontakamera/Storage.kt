package fi.valvontakamera

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Yksi tallennettu videopätkä. */
class Seg(val file: File, val start: Long, val end: Long)

/** Yksi liiketapahtuma. */
class Ev(val start: Long, val end: Long, val peak: Float, val still: String)

/**
 * Tallennusrakenne:  <ulkoinen sovelluskansio>/yot/<yyyy-MM-dd>/
 *   seg_<ms>.mp4      säilytetyt videopätkät
 *   still_<ms>.jpg    kuva jokaisen tapahtuman alusta
 *   segments.csv      start,end,tiedosto
 *   activity.csv      sekunti,liikemäärä (vain kun > 0)
 *   events.csv        start,end,huippu,kuva
 *   sessions.csv      S/E,aika   (valvonnan käynnistys / pysäytys)
 * "Yö" vaihtuu keskipäivällä: klo 12–12 kuuluu samaan yöhön.
 */
object Storage {
    private val nightFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val nightRe = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun root(c: Context): File =
        File(c.getExternalFilesDir(null) ?: c.filesDir, "yot").apply { mkdirs() }

    fun nightKey(ms: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        if (cal.get(Calendar.HOUR_OF_DAY) < 12) cal.add(Calendar.DAY_OF_MONTH, -1)
        synchronized(nightFmt) { return nightFmt.format(cal.time) }
    }

    fun nightDir(c: Context, ms: Long): File = File(root(c), nightKey(ms)).apply { mkdirs() }

    fun nights(c: Context): List<File> =
        root(c).listFiles()?.filter { it.isDirectory && it.name.matches(nightRe) }
            ?.sortedByDescending { it.name } ?: emptyList()

    @Synchronized
    fun append(f: File, line: String) {
        try {
            FileWriter(f, true).use { it.write(line); it.write("\n") }
        } catch (_: Exception) {
        }
    }

    fun appendSegment(c: Context, s: Seg) =
        append(File(s.file.parentFile, "segments.csv"), "${s.start},${s.end},${s.file.name}")

    fun appendActivity(c: Context, sec: Long, score: Float) =
        append(File(nightDir(c, sec * 1000), "activity.csv"), "$sec,${"%.5f".format(Locale.US, score)}")

    fun appendEvent(c: Context, e: Ev) =
        append(File(nightDir(c, e.start), "events.csv"),
            "${e.start},${e.end},${"%.5f".format(Locale.US, e.peak)},${e.still}")

    fun appendSession(c: Context, kind: String, t: Long) =
        append(File(nightDir(c, t), "sessions.csv"), "$kind,$t")

    private fun rows(f: File): List<List<String>> =
        if (!f.exists()) emptyList()
        else try {
            f.readLines().map { it.trim().split(',') }.filter { it.size >= 2 }
        } catch (_: Exception) {
            emptyList()
        }

    fun readSegments(dir: File): List<Seg> =
        rows(File(dir, "segments.csv")).mapNotNull { r ->
            val f = File(dir, r.getOrNull(2) ?: return@mapNotNull null)
            val a = r[0].toLongOrNull() ?: return@mapNotNull null
            val b = r[1].toLongOrNull() ?: return@mapNotNull null
            if (f.exists() && b > a) Seg(f, a, b) else null
        }.sortedBy { it.start }

    fun readEvents(dir: File): List<Ev> =
        rows(File(dir, "events.csv")).mapNotNull { r ->
            val a = r[0].toLongOrNull() ?: return@mapNotNull null
            val b = r[1].toLongOrNull() ?: return@mapNotNull null
            Ev(a, b, r.getOrNull(2)?.toFloatOrNull() ?: 0f, r.getOrNull(3) ?: "")
        }.sortedBy { it.start }

    /** Palauttaa (ajat ms, arvot). */
    fun readActivity(dir: File): Pair<LongArray, FloatArray> {
        val r = rows(File(dir, "activity.csv")).mapNotNull { row ->
            val s = row[0].toLongOrNull() ?: return@mapNotNull null
            val v = row[1].toFloatOrNull() ?: return@mapNotNull null
            s * 1000 to v
        }.sortedBy { it.first }
        return LongArray(r.size) { r[it].first } to FloatArray(r.size) { r[it].second }
    }

    fun readSessionTimes(dir: File): List<Long> =
        rows(File(dir, "sessions.csv")).mapNotNull { it[1].toLongOrNull() }

    fun dirSize(d: File): Long = d.listFiles()?.sumOf { it.length() } ?: 0L

    fun deleteNight(d: File) {
        d.listFiles()?.forEach { it.delete() }
        d.delete()
    }

    /** Poistaa vanhimmat yöt: yli [keep] yötä tai vapaata tilaa alle [minFree]. Kuluvaa yötä ei poisteta. */
    fun cleanup(c: Context, keep: Int, minFree: Long) {
        val current = nightKey(System.currentTimeMillis())
        val list = nights(c).toMutableList()   // uusin ensin
        while (list.size > keep.coerceAtLeast(1)) {
            val d = list.removeAt(list.size - 1)
            if (d.name != current) deleteNight(d)
        }
        while (root(c).usableSpace < minFree && list.size > 1) {
            val d = list.removeAt(list.size - 1)
            if (d.name != current) deleteNight(d) else break
        }
    }
}

object Fmt {
    private val t = SimpleDateFormat("HH:mm:ss", Locale("fi"))
    private val hm = SimpleDateFormat("HH:mm", Locale("fi"))
    private val dt = SimpleDateFormat("d.M. 'klo' HH:mm:ss", Locale("fi"))
    private val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("fi"))
    fun time(ms: Long): String = synchronized(t) { t.format(Date(ms)) }
    fun hm(ms: Long): String = synchronized(hm) { hm.format(Date(ms)) }
    fun dateTime(ms: Long): String = synchronized(dt) { dt.format(Date(ms)) }
    fun stamp(ms: Long): String = synchronized(stamp) { stamp.format(Date(ms)) }
    fun size(b: Long): String =
        if (b >= 1_000_000_000L) "%.1f Gt".format(Locale("fi"), b / 1e9)
        else "%.0f Mt".format(Locale("fi"), b / 1e6)
}
