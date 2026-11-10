package fi.valvontakamera

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Liiketunnistus kuvan kirkkaustasosta (Y).
 *
 * 1. Kuva pienennetään 160 x ~90 ruudukoksi (jokainen solu = 16 näytteen keskiarvo).
 * 2. Verrataan hitaasti päivittyvään taustakuvaan.
 * 3. Morfologinen avaus (eroosio + dilaatio) poistaa pienet irralliset muutokset:
 *    sadepisarat, lumihiutaleet, hyönteiset ja kohinan.
 * 4. Yhtenäiset alueet (blobit), joiden koko ylittää herkkyyden mukaisen rajan, hyväksytään.
 * 5. Blobin pitää näkyä peräkkäisissä kuvissa suunnilleen samassa kohdassa
 *    (pisarat putoavat nopeasti eivätkä täytä ehtoa). Sateella vaaditaan useampi kuva.
 * 6. Jos lähes koko kuva muuttuu kerralla (valo syttyy/sammuu), tausta opetellaan uudelleen.
 */
class MotionDetector {
    val gw = 160
    var gh = 90; private set
    private var n = 0
    private var fw = 0
    private var fh = 0
    private var fRs = -1
    private var fPs = -1
    private var sampleOff = IntArray(0)
    private var cur = FloatArray(0)
    private var bg = FloatArray(0)
    private var raw = BooleanArray(0)
    private var ero = BooleanArray(0)
    private var mask = BooleanArray(0)
    private var labels = IntArray(0)
    private var queue = IntArray(0)

    /** 0 = ei mitään, 1 = liikettä (ei vielä vahvistettu), 2 = vahvistettu liike. */
    var overlay = ByteArray(0); private set

    private var hasBg = false
    private var prev = ArrayList<IntArray>()
    private var hits = 0
    private var misses = 0

    @Volatile var sensitivity = 6
    var rainLevel = 0f; private set

    class Result(val score: Float, val triggered: Boolean, val lightChange: Boolean)

    private fun setup(w: Int, h: Int, rs: Int, ps: Int) {
        fw = w; fh = h; fRs = rs; fPs = ps
        gh = max(1, (gw.toFloat() * h / w).roundToInt())
        n = gw * gh
        cur = FloatArray(n); bg = FloatArray(n)
        raw = BooleanArray(n); ero = BooleanArray(n); mask = BooleanArray(n)
        labels = IntArray(n); queue = IntArray(n); overlay = ByteArray(n)
        sampleOff = IntArray(n * 16)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x0 = gx * w / gw; val x1 = (gx + 1) * w / gw
            val y0 = gy * h / gh; val y1 = (gy + 1) * h / gh
            val base = (gy * gw + gx) * 16
            var k = 0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val x = (x0 + (x1 - x0) * (2 * sx + 1) / 8).coerceIn(0, w - 1)
                val y = (y0 + (y1 - y0) * (2 * sy + 1) / 8).coerceIn(0, h - 1)
                sampleOff[base + k++] = y * rs + x * ps
            }
        }
        hasBg = false
        prev.clear(); hits = 0; misses = 0
    }

    fun reset() {
        hasBg = false
        prev.clear(); hits = 0; misses = 0
    }

    fun process(buf: ByteBuffer, rs: Int, ps: Int, w: Int, h: Int): Result {
        if (w != fw || h != fh || rs != fRs || ps != fPs) setup(w, h, rs, ps)
        val lim = buf.limit()
        for (i in 0 until n) {
            var s = 0
            val b = i * 16
            for (k in 0 until 16) {
                val o = sampleOff[b + k]
                if (o < lim) s += buf.get(o).toInt() and 0xFF
            }
            cur[i] = s / 16f
        }
        if (!hasBg) {
            System.arraycopy(cur, 0, bg, 0, n)
            hasBg = true
            overlay.fill(0)
            return Result(0f, false, false)
        }

        // Kohinataso ja kynnys
        var sum = 0f
        for (i in 0 until n) sum += abs(cur[i] - bg[i])
        val mean = sum / n
        val s = sensitivity.coerceIn(1, 10)
        val thr = (34f - 2.6f * s) + 1.5f * mean

        var changed = 0
        for (i in 0 until n) {
            val c = abs(cur[i] - bg[i]) > thr
            raw[i] = c
            if (c) changed++
        }

        // Koko kuva muuttui (valo syttyi / pilvi / automaattivalotus): opetellaan tausta uudelleen
        if (changed > n * 0.45f) {
            System.arraycopy(cur, 0, bg, 0, n)
            prev.clear(); hits = 0; misses = 0
            overlay.fill(0)
            return Result(0f, false, true)
        }

        // Eroosio: solu säilyy vain jos vähintään 7/9 naapurustosta on muuttunut
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val i = gy * gw + gx
            if (!raw[i] || gx == 0 || gy == 0 || gx == gw - 1 || gy == gh - 1) { ero[i] = false; continue }
            var cnt = 0
            for (dy in -1..1) for (dx in -1..1) if (raw[i + dy * gw + dx]) cnt++
            ero[i] = cnt >= 7
        }
        // Dilaatio
        var removed = 0
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val i = gy * gw + gx
            var m = false
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val x = gx + dx; val y = gy + dy
                if (x < 0 || y < 0 || x >= gw || y >= gh) continue
                if (ero[y * gw + x]) { m = true; break@loop }
            }
            mask[i] = m
            if (raw[i] && !m) removed++
        }
        // Sadetaso: kuinka paljon pientä "roskaa" suodatettiin pois
        rainLevel = rainLevel * 0.95f + 0.05f * (removed.toFloat() / n)

        // Yhtenäiset alueet
        labels.fill(0)
        var label = 0
        val blobs = ArrayList<IntArray>() // x0,y0,x1,y1,area,label
        for (i in 0 until n) {
            if (!mask[i] || labels[i] != 0) continue
            label++
            var qh = 0; var qt = 0
            queue[qt++] = i; labels[i] = label
            var x0 = gw; var y0 = gh; var x1 = 0; var y1 = 0; var area = 0
            while (qh < qt) {
                val j = queue[qh++]
                val x = j % gw; val y = j / gw
                area++
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x > 0 && mask[j - 1] && labels[j - 1] == 0) { labels[j - 1] = label; queue[qt++] = j - 1 }
                if (x < gw - 1 && mask[j + 1] && labels[j + 1] == 0) { labels[j + 1] = label; queue[qt++] = j + 1 }
                if (y > 0 && mask[j - gw] && labels[j - gw] == 0) { labels[j - gw] = label; queue[qt++] = j - gw }
                if (y < gh - 1 && mask[j + gw] && labels[j + gw] == 0) { labels[j + gw] = label; queue[qt++] = j + gw }
            }
            blobs.add(intArrayOf(x0, y0, x1, y1, area, label))
        }

        val minArea = max(3, (70.0 * 0.72.pow(s - 1)).toInt())
        val valid = blobs.filter { it[4] >= minArea }
        overlay.fill(0)
        var persistArea = 0
        val pad = 3
        for (b in valid) {
            val persistent = prev.any { p ->
                b[0] - pad <= p[2] && b[2] + pad >= p[0] && b[1] - pad <= p[3] && b[3] + pad >= p[1]
            }
            if (persistent) persistArea += b[4]
            val mark: Byte = if (persistent) 2 else 1
            for (y in b[1]..b[3]) for (x in b[0]..b[2]) {
                val j = y * gw + x
                if (labels[j] == b[5]) overlay[j] = mark
            }
        }
        prev = ArrayList(valid)

        if (persistArea > 0) { hits++; misses = 0 } else { misses++; if (misses > 1) hits = 0 }
        val need = if (rainLevel > 0.01f) 5 else 3
        val triggered = hits >= need

        // Taustan päivitys: liikealueilla hitaasti, muualla nopeammin
        for (i in 0 until n) {
            val a = if (mask[i]) 0.01f else 0.06f
            bg[i] += a * (cur[i] - bg[i])
        }
        return Result(persistArea.toFloat() / n, triggered, false)
    }
}
