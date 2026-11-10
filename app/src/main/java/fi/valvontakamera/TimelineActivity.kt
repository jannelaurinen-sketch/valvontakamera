package fi.valvontakamera

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import java.io.File

/** Yön nauhoitusten katselu: video + liikekäyrä jolla kelataan. */
class TimelineActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var tl: TimelineView
    private lateinit var info: TextView
    private var segs = listOf<Seg>()
    private var events = listOf<Ev>()
    private var byPeak = listOf<Ev>()
    private var peakIdx = -1
    private var lastSeek = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(Storage.root(this), intent.getStringExtra("night") ?: "")
        segs = Storage.readSegments(dir)
        events = Storage.readEvents(dir)
        byPeak = events.sortedByDescending { it.peak }
        val (actT, actV) = Storage.readActivity(dir)

        player = ExoPlayer.Builder(this).build()
        val pv = PlayerView(this).apply { this.player = this@TimelineActivity.player; setBackgroundColor(Color.BLACK) }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        col.add(pv, 0)
        (pv.layoutParams as LinearLayout.LayoutParams).weight = 1f

        info = label("", 14f)
        col.add(info)
        tl = TimelineView(this)
        col.add(tl, dp(150))
        col.add(row(
            button("⏮ Edellinen") { prevEvent() },
            button("★ Eniten liikettä") { biggest() },
            button("Seuraava ⏭") { nextEvent() }
        ))
        col.add(row(
            button("½×") { speed(0.5f) },
            button("1×") { speed(1f) },
            button("2×") { speed(2f) },
            button("4×") { speed(4f) },
            button("8×") { speed(8f) }
        ))
        col.add(row(
            button("Koko yö") { tl.zoomAll() },
            button("Tallenna pätkä galleriaan") { exportCurrent() }
        ))
        setContentView(col)

        tl.setData(segs, events, actT, actV, Storage.readSessionTimes(dir))
        tl.onScrub = { t, final -> seekTo(t, final) }

        if (segs.isEmpty()) {
            info.text = "Tältä yöltä ei ole tallennettuja videoita."
        } else {
            player.setMediaItems(segs.map { MediaItem.fromUri(Uri.fromFile(it.file)) })
            player.prepare()
            info.text = "${events.size} tapahtumaa. Vedä aikajanaa sormella, zoomaa kahdella sormella."
        }
        h.post(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!tl.dragging && segs.isNotEmpty()) {
                val i = player.currentMediaItemIndex
                if (i in segs.indices) {
                    val t = segs[i].start + player.currentPosition
                    tl.cursor = t
                    tl.ensureVisible(t)
                    tl.invalidate()
                    if (player.isPlaying) info.text = describe(t)
                }
            }
            h.postDelayed(this, 200)
        }
    }

    private fun describe(t: Long): String {
        val ev = events.indexOfFirst { t >= it.start - CameraService.PRE_MS && t <= it.end + CameraService.POST_MS }
        val inSeg = segs.any { t >= it.start && t < it.end }
        return Fmt.dateTime(t) + when {
            ev >= 0 -> "  ·  tapahtuma ${ev + 1}/${events.size}"
            !inSeg -> "  ·  ei videota tässä kohdassa"
            else -> ""
        }
    }

    private fun seekTo(t: Long, exact: Boolean) {
        info.text = describe(t)
        if (segs.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (!exact && now - lastSeek < 120) return
        lastSeek = now
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        var i = segs.indexOfFirst { t < it.end }
        if (i < 0) i = segs.size - 1
        val s = segs[i]
        player.seekTo(i, (t - s.start).coerceIn(0L, s.end - s.start))
    }

    private fun jump(t: Long) {
        tl.cursor = t
        tl.ensureVisible(t)
        tl.invalidate()
        seekTo(t, true)
        player.play()
    }

    private fun prevEvent() {
        val e = events.lastOrNull { it.start < tl.cursor - 4000 } ?: run { toast("Ei aiempia tapahtumia"); return }
        jump(e.start - 3000)
    }

    private fun nextEvent() {
        val e = events.firstOrNull { it.start > tl.cursor + 1000 } ?: run { toast("Ei myöhempiä tapahtumia"); return }
        jump(e.start - 3000)
    }

    private fun biggest() {
        if (byPeak.isEmpty()) { toast("Ei tapahtumia"); return }
        peakIdx = (peakIdx + 1) % byPeak.size
        val e = byPeak[peakIdx]
        toast("Liikettä eniten: ${peakIdx + 1}/${byPeak.size}  (${Fmt.time(e.start)})")
        jump(e.start - 3000)
    }

    private fun speed(v: Float) {
        player.setPlaybackSpeed(v)
        toast("Nopeus ${v}×")
    }

    private fun exportCurrent() {
        val i = player.currentMediaItemIndex
        if (i !in segs.indices) return
        val s = segs[i]
        Thread {
            val ok = try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "valvonta_${s.file.nameWithoutExtension}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Valvonta")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { out -> s.file.inputStream().use { it.copyTo(out) } }
                true
            } catch (_: Exception) { false }
            runOnUiThread { toast(if (ok) "Tallennettu galleriaan (Movies/Valvonta)" else "Tallennus epäonnistui") }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        player.pause()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }
}
