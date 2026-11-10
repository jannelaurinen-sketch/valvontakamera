package fi.valvontakamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.drawable.Icon
import android.media.MediaMetadataRetriever
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Valvontapalvelu. Kamera kuvaa koko ajan 30 s videopätkiä; pätkä säilytetään vain jos
 * sen aikana (tai 8 s ennen/jälkeen) havaittiin liikettä. Näin tallenteeseen tulee mukaan
 * myös hetki ennen liikettä. Muut pätkät poistetaan heti.
 */
class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "Valvonta"
        const val ACTION_STOP = "fi.valvontakamera.STOP"
        const val EXTRA_ROT = "rot"
        const val SEG_MS = 30_000L          // normaali pätkän pituus
        const val SEG_MAX_MS = 180_000L     // liikkeen aikana pätkää jatketaan enintään tähän asti
        const val PRE_MS = 2_000L           // esitallennus
        const val POST_MS = 2_000L          // jälkitallennus viimeisen liikkeen jälkeen
        const val QUIET_MS = 2_000L         // tapahtuma päättyy kun liikettä ei ole näkynyt näin pitkään
        const val CLIP_MAX_MS = 120_000L    // Telegram-klipin maksimipituus
        const val MIN_FREE = 4_000_000_000L
        private const val NOTIF_ID = 1
        private const val CH = "valvonta"

        @Volatile var instance: CameraService? = null
        /** Asetetaan pääsäikeessä; kutsutaan pääsäikeessä. */
        @Volatile var previewListener: ((Bitmap) -> Unit)? = null

        fun start(c: Context, rotation: Int) {
            ContextCompat.startForegroundService(c, Intent(c, CameraService::class.java).putExtra(EXTRA_ROT, rotation))
        }

        fun stop(c: Context) {
            instance?.beginStop()
        }
    }

    // --- tila käyttöliittymälle ---
    @Volatile var running = false; private set
    @Volatile var resolutionText = ""; private set
    @Volatile var lastScore = 0f; private set
    @Volatile var lastMotionAt = 0L; private set
    @Volatile var rainLevel = 0f; private set
    @Volatile var errorText = ""; private set
    @Volatile var batteryText = ""; private set
    @Volatile var torchOn = false; private set
    var eventsCount = 0; private set
    var keptCount = 0; private set
    val eventActive get() = activeEventStart != 0L

    private val main = Handler(Looper.getMainLooper())
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val detector = MotionDetector()
    private var rotation = Surface.ROTATION_0
    private var audioOn = false
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var recorder: Recorder? = null
    private var activeRecording: Recording? = null
    private var activeRecStart = Long.MAX_VALUE
    @Volatile private var stopping = false
    private var finished = false
    private var failCount = 0
    private val pendingSegs = ArrayList<Seg>()
    private val keptSegs = ArrayList<Seg>()
    private val recentEvents = ArrayList<LongArray>()
    private val pendingClips = ArrayList<LongArray>()   // start, end, vastattava viesti-id
    private val requested = HashMap<Long, Long>()      // tapahtuman alku -> pyyntöaika
    @Volatile private var stillRequest = false
    private var activeEventStart = 0L
    private var transforming = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var tickCount = 0
    private var hotWarned = false
    private var unpluggedWarned = false
    private var lastNotifText = ""

    // --- analyysisäikeen tila ---
    private var lastProc = 0L
    private var lastPreview = 0L
    private var curSec = 0L
    private var secMax = 0f
    private var evStart = 0L
    private var evLast = 0L
    private var evPeak = 0f
    private var bestNv21: ByteArray? = null
    private var bestW = 0
    private var bestH = 0
    private var bestRot = 0
    private var bestAt = 0L
    private var lastGrab = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            if (running) beginStop() else stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        rotation = intent?.getIntExtra(EXTRA_ROT, Surface.ROTATION_0) ?: Surface.ROTATION_0
        audioOn = Prefs.audio(this) && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!startFg()) { stopSelf(); return START_NOT_STICKY }
        running = true
        instance = this
        detector.sensitivity = Prefs.sensitivity(this)

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "valvonta:cpu").apply { acquire() }
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "valvonta:wifi").apply { acquire() }
        } catch (t: Throwable) { Log.w(TAG, "wifilock", t) }

        Storage.cleanup(this, Prefs.keepNights(this), MIN_FREE)
        Storage.appendSession(this, "S", System.currentTimeMillis())
        Telegram.text(this, "📹 Valvonta käynnistyi")
        Telegram.startPolling(this,
            { cb -> main.post { onVideoRequest(cb) } },
            { cmd -> main.post { onCommand(cmd) } })

        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                provider = f.get()
                bindCamera()
            } catch (t: Throwable) {
                fail("Kamera ei käynnisty: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(this))
        main.postDelayed(tick, 3000)
        return START_NOT_STICKY
    }

    private fun startFg(): Boolean = try {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Valvonta", NotificationManager.IMPORTANCE_LOW))
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (audioOn) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotif("Käynnistyy…"), type)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground", t)
        errorText = "Palvelu ei käynnisty: ${t.message}"
        false
    }

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, CameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Valvontakamera")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Pysäytä", stopPi).build())
            .build()
    }

    private fun updateNotif() {
        val t = when {
            errorText.isNotEmpty() -> "⚠ $errorText"
            eventActive -> "LIIKETTÄ – nauhoitetaan"
            else -> "Valvoo · $eventsCount tapahtumaa"
        }
        if (t == lastNotifText) return
        lastNotifText = t
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(t))
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ kamera

    private fun bindCamera() {
        val wanted = if (Prefs.quality(this) == "HD") Quality.HD else Quality.FHD
        try {
            bindWith(wanted)
        } catch (t: Throwable) {
            Log.w(TAG, "bind $wanted", t)
            if (wanted == Quality.FHD) bindWith(Quality.HD) else throw t
        }
        startSegment()
    }

    private fun bindWith(q: Quality) {
        val p = provider ?: return
        val rec = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
            .build()
        val vc = VideoCapture.withOutput(rec)
        vc.targetRotation = rotation
        val an = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .build()
        an.setAnalyzer(analysisExec) { img -> analyze(img) }
        p.unbindAll()
        camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, vc, an)
        recorder = rec
        applyTorch(Prefs.torch(this))
    }

    /** Puhelimen oma valo päälle/pois. */
    fun applyTorch(on: Boolean) {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        try {
            cam.cameraControl.enableTorch(on)
            torchOn = on
        } catch (t: Throwable) { Log.w(TAG, "torch", t) }
    }

    fun setSensitivity(v: Int) { detector.sensitivity = v }

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        val rec = recorder ?: return
        if (!running || stopping) return
        val now = System.currentTimeMillis()
        val file = File(Storage.nightDir(this, now), "seg_$now.mp4")
        val startHolder = longArrayOf(now)
        var pr = rec.prepareRecording(this, FileOutputOptions.Builder(file).build())
        if (audioOn) pr = pr.withAudioEnabled()
        activeRecStart = now
        try {
            activeRecording = pr.start(ContextCompat.getMainExecutor(this)) { ev -> onRecordEvent(ev, file, startHolder) }
        } catch (t: Throwable) {
            Log.e(TAG, "record start", t)
            activeRecStart = Long.MAX_VALUE
            failCount++
            if (failCount >= 5) fail("Videotallennus ei käynnisty: ${t.message}")
            else main.postDelayed({ startSegment() }, 3000)
            return
        }
        main.removeCallbacks(segRoll)
        main.postDelayed(segRoll, SEG_MS)
    }

    private val segRoll = object : Runnable {
        override fun run() {
            val r = activeRecording ?: return
            if (activeEventStart != 0L && System.currentTimeMillis() - activeRecStart < SEG_MAX_MS) {
                main.postDelayed(this, 5000)   // ei katkaista pätkää kesken liikkeen
                return
            }
            r.stop()
        }
    }

    private fun onRecordEvent(ev: VideoRecordEvent, file: File, startHolder: LongArray) {
        when (ev) {
            is VideoRecordEvent.Start -> {
                startHolder[0] = System.currentTimeMillis()
                activeRecStart = startHolder[0]
            }
            is VideoRecordEvent.Finalize -> {
                activeRecording = null
                activeRecStart = Long.MAX_VALUE
                main.removeCallbacks(segRoll)
                val dur = ev.recordingStats.recordedDurationNanos / 1_000_000L
                val ok = file.exists() && file.length() > 0 && dur > 300
                if (ok) {
                    pendingSegs.add(Seg(file, startHolder[0], startHolder[0] + dur))
                    failCount = 0
                    if (resolutionText.isEmpty()) readResolution(file)
                } else {
                    file.delete()
                    failCount++
                    Log.w(TAG, "segment failed error=${ev.error}")
                }
                if (stopping) { finishStop(); return }
                decideSegments()
                if (failCount >= 5) { fail("Videotallennus epäonnistuu (virhe ${ev.error})"); return }
                if (running) {
                    if (failCount > 0) main.postDelayed({ startSegment() }, 2000) else startSegment()
                }
            }
            else -> {}
        }
    }

    private fun readResolution(f: File) {
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            r.release()
            if (w != null && h != null) resolutionText = "${w}×${h}"
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ pätkien säilytys

    private fun overlapsEvent(a: Long, b: Long): Boolean {
        if (activeEventStart != 0L && activeEventStart - PRE_MS <= b) return true
        return recentEvents.any { it[0] - PRE_MS <= b && it[1] + POST_MS >= a }
    }

    private fun keep(s: Seg) {
        keptSegs.add(s)
        keptCount++
        Storage.appendSegment(this, s)
    }

    private fun decideSegments() {
        val now = System.currentTimeMillis()
        val it = pendingSegs.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (overlapsEvent(s.start, s.end)) { keep(s); it.remove() }
            else if (now > s.end + PRE_MS + 1500) { s.file.delete(); it.remove() }
        }
        val old = now - 3_600_000L
        recentEvents.removeAll { it[1] < old }
        keptSegs.removeAll { it.end < old }
    }

    // ------------------------------------------------------------------ Telegram-klipit

    /** Joku painoi Telegramissa "Lataa video" -painiketta. */
    private fun onVideoRequest(cb: Telegram.Callback) {
        val parts = cb.data.split(':')
        if (parts.size < 3 || parts[0] != "v") return
        val st = parts[1].toLongOrNull() ?: return
        val en = parts[2].toLongOrNull() ?: return
        val now = System.currentTimeMillis()
        val prev = requested[st]
        if (prev != null && now - prev < 10 * 60_000L) return   // sama video jo pyydetty
        requested[st] = now
        requested.entries.removeAll { now - it.value > 3_600_000L }
        pendingClips.add(longArrayOf(st, en, cb.msgId, 0L))
        processClips()
    }

    /** Telegram-ryhmän komennot. */
    private fun onCommand(cmd: Telegram.Command) {
        if (!running || stopping) return
        when (cmd.cmd) {
            "kuva", "photo" -> stillRequest = true
            "video", "live" -> {
                val sec = (cmd.arg.filter { it.isDigit() }.toIntOrNull() ?: 10).coerceIn(3, 60)
                val now = System.currentTimeMillis()
                val end = now + sec * 1000L
                // pidetään tämän jakson videopätkät tallessa
                recentEvents.add(longArrayOf(now + PRE_MS, end - POST_MS))
                Telegram.text(this, "🎥 Kuvataan $sec s, video tulee pian…")
                pendingClips.add(longArrayOf(now, end, cmd.msgId, 1L))
                // katkaistaan nauhoitus jakson lopussa, jotta video saadaan heti
                main.postDelayed({
                    if (running && !stopping && activeRecStart < end) activeRecording?.stop()
                }, sec * 1000L + 500)
            }
            "tila", "status" -> {
                val sb = StringBuilder("📊 Valvonta käynnissä\n")
                if (resolutionText.isNotEmpty()) sb.append("Video $resolutionText\n")
                sb.append("Tapahtumia: $eventsCount\n")
                if (lastMotionAt > 0) sb.append("Viimeisin liike: ${Fmt.dateTime(lastMotionAt)}\n")
                if (batteryText.isNotEmpty()) sb.append(batteryText).append("\n")
                sb.append("Valo: ${if (torchOn) "päällä" else "pois"}\n")
                sb.append("Vapaata muistia: ${Fmt.size(Storage.root(this).usableSpace)}")
                if (errorText.isNotEmpty()) sb.append("\n⚠ $errorText")
                Telegram.text(this, sb.toString())
            }
            "valo", "light" -> {
                val on = when (cmd.arg.lowercase()) {
                    "päälle", "on", "1" -> true
                    "pois", "off", "0" -> false
                    else -> !torchOn
                }
                Prefs.setTorch(this, on)
                applyTorch(on)
                Telegram.text(this, if (on) "💡 Valo päällä" else "🌑 Valo pois")
            }
            "start", "apua", "help" -> Telegram.text(this,
                "Komennot:\n/kuva – kuva pihalta nyt\n/video – 10 s video nyt (esim. /video 30)\n" +
                "/tila – kameran tila\n/valo – puhelimen valo päälle/pois")
        }
    }

    private fun processClips() {
        if (transforming || pendingClips.isEmpty()) return
        val ev = pendingClips[0]
        val live = ev.size > 3 && ev[3] == 1L
        val cs = if (live) ev[0] else ev[0] - PRE_MS
        val ce = if (live) ev[1] else minOf(ev[1] + POST_MS, cs + CLIP_MAX_MS)
        if (activeRecStart <= ce) return                  // jakso vielä nauhoituksessa
        if (pendingSegs.any { it.start <= ce && it.end >= cs }) return
        pendingClips.removeAt(0)
        val segs = Storage.readSegments(Storage.nightDir(this, ev[0])).filter { it.start < ce && it.end > cs }
        if (segs.isEmpty()) {
            Telegram.text(this, "Video ${Fmt.time(ev[0])} ei ole enää tallessa.")
            processClips(); return
        }
        transforming = true
        val out = File(cacheDir, "klippi_${ev[0]}.mp4")
        val caption = if (live) "🎥 Nyt ${Fmt.time(ev[0])} (${(ev[1] - ev[0]) / 1000} s)"
                      else "🎬 ${Fmt.time(ev[0])}–${Fmt.time(ev[1])} (${(ev[1] - ev[0]) / 1000 + 1} s)"
        ClipMaker.make(this, segs, cs, ce, out) { ok, err ->
            transforming = false
            if (ok && out.exists()) Telegram.video(this, out, caption, ev[2])
            else {
                out.delete()
                Telegram.text(this, "Videon pakkaus epäonnistui (${Fmt.time(ev[0])}): $err")
            }
            if (running) main.post { processClips() }
        }
    }

    // ------------------------------------------------------------------ analyysi (analyysisäie)

    private fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastProc < 100) return
            lastProc = now
            if (stopping) return
            val p = image.planes[0]
            val r = detector.process(p.buffer, p.rowStride, p.pixelStride, image.width, image.height)
            lastScore = r.score
            rainLevel = detector.rainLevel

            if (previewListener != null && now - lastPreview > 250) {
                lastPreview = now
                val bmp = makePreview(image)
                main.post { previewListener?.invoke(bmp) }
            }

            // Liikemäärä sekunneittain aikajanaa varten
            val sec = now / 1000
            if (sec != curSec) {
                if (secMax > 0f && curSec > 0) Storage.appendActivity(this, curSec, secMax)
                curSec = sec; secMax = 0f
            }
            if (r.score > secMax) secMax = r.score

            if (stillRequest) {
                stillRequest = false
                try {
                    val w = image.width and 1.inv(); val h = image.height and 1.inv()
                    val buf = ByteArray(w * h * 3 / 2)
                    toNv21(image, w, h, buf)
                    val f = saveJpeg(buf, w, h, image.imageInfo.rotationDegrees, now, "kuva_$now.jpg")
                    main.post { Telegram.photo(this, f, "📷 Nyt ${Fmt.dateTime(now)}") }
                } catch (t: Throwable) { Log.w(TAG, "kuva", t) }
            }

            if (r.triggered) {
                if (evStart == 0L) {
                    evStart = now; evPeak = 0f; bestAt = 0L; lastGrab = 0L
                    main.post { onEventStart(now) }
                }
                evLast = now
                lastMotionAt = now
                if (r.score > evPeak) {
                    evPeak = r.score
                    // talteen kuva kohdasta, jossa liikettä on eniten
                    if (bestAt == 0L || now - lastGrab > 300) {
                        lastGrab = now
                        try { grabBest(image, now) } catch (t: Throwable) { Log.w(TAG, "grab", t) }
                    }
                }
            }
            if (evStart != 0L && now - evLast > QUIET_MS) {
                val still = try { if (bestAt != 0L) saveBest() else null } catch (t: Throwable) { Log.w(TAG, "still", t); null }
                val e = Ev(evStart, evLast, evPeak, still?.name ?: "")
                evStart = 0L
                main.post { onEventEnd(e, still) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "analyze", t)
        } finally {
            image.close()
        }
    }

    private fun onEventStart(t: Long) {
        if (!running || stopping) return
        activeEventStart = t
        eventsCount++
        updateNotif()
    }

    private fun onEventEnd(e: Ev, still: File?) {
        if (!running || stopping) return
        recentEvents.add(longArrayOf(e.start, e.end))
        Storage.appendEvent(this, e)
        if (still != null) {
            val dur = (e.end - e.start) / 1000 + 1
            Telegram.photo(this, still,
                "🚶 Liikettä ${Fmt.dateTime(e.start)} – ${Fmt.time(e.end)} ($dur s)",
                "📥 Lataa video ($dur s)", "v:${e.start}:${e.end}")
        }
        if (activeEventStart == e.start) activeEventStart = 0L
        decideSegments()
        processClips()
        updateNotif()
    }

    /** Pieni harmaasävykuva esikatseluun; havaittu liike väritetään (keltainen = ehdokas, punainen = liike). */
    private fun makePreview(image: ImageProxy): Bitmap {
        val gw = detector.gw
        val gh = detector.gh
        val ov = detector.overlay
        val sc = 3
        val pw = gw * sc
        val ph = gh * sc
        val p = image.planes[0]
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val w = image.width
        val h = image.height
        val lim = buf.limit()
        val px = IntArray(pw * ph)
        for (y in 0 until ph) {
            val sy = y * h / ph
            val oy = (y / sc).coerceAtMost(gh - 1) * gw
            for (x in 0 until pw) {
                val o = sy * rs + (x * w / pw) * ps
                val v = if (o < lim) buf.get(o).toInt() and 0xFF else 0
                val m = if (ov.size == gw * gh) ov[oy + x / sc].toInt() else 0
                px[y * pw + x] = when (m) {
                    2 -> Color.rgb(128 + v / 2, v / 3, v / 3)
                    1 -> Color.rgb(100 + v / 2, 100 + v / 2, v / 3)
                    else -> Color.rgb(v, v, v)
                }
            }
        }
        var bmp = Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888)
        val rot = image.imageInfo.rotationDegrees
        if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, pw, ph, Matrix().apply { postRotate(rot.toFloat()) }, false)
        return bmp
    }

    /** Kopioi nykyisen kuvan talteen (tapahtuman suurin liike tähän mennessä). */
    private fun grabBest(image: ImageProxy, now: Long) {
        val w = image.width and 1.inv()
        val h = image.height and 1.inv()
        var buf = bestNv21
        if (buf == null || buf.size != w * h * 3 / 2) { buf = ByteArray(w * h * 3 / 2); bestNv21 = buf }
        toNv21(image, w, h, buf)
        bestW = w; bestH = h; bestRot = image.imageInfo.rotationDegrees; bestAt = now
    }

    /** Tallentaa talteen otetun kuvan JPEG-tiedostoksi aikaleiman kanssa. */
    private fun saveBest(): File = saveJpeg(bestNv21!!, bestW, bestH, bestRot, bestAt, "still_$bestAt.jpg")

    private fun saveJpeg(nv21: ByteArray, w: Int, h: Int, rot: Int, now: Long, name: String): File {
        val bestRot = rot
        val baos = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 92, baos)
        var bmp = BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())
        bmp = if (bestRot != 0)
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(bestRot.toFloat()) }, true)
        else bmp.copy(Bitmap.Config.ARGB_8888, true)
        if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = bmp.height / 22f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        c.drawText(Fmt.stamp(now), paint.textSize * 0.5f, bmp.height - paint.textSize * 0.6f, paint)
        val f = File(Storage.nightDir(this, now), name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    private fun toNv21(image: ImageProxy, w: Int, h: Int, out: ByteArray) {
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yb = yP.buffer; val yrs = yP.rowStride; val yps = yP.pixelStride
        var pos = 0
        for (row in 0 until h) {
            if (yps == 1) {
                yb.position(row * yrs)
                yb.get(out, pos, w)
                pos += w
            } else {
                for (col in 0 until w) out[pos++] = yb.get(row * yrs + col * yps)
            }
        }
        yb.rewind()
        val ub = uP.buffer; val vb = vP.buffer
        val urs = uP.rowStride; val ups = uP.pixelStride
        val vrs = vP.rowStride; val vps = vP.pixelStride
        val ulim = ub.limit(); val vlim = vb.limit()
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            val vo = row * vrs + col * vps
            val uo = row * urs + col * ups
            out[pos++] = if (vo < vlim) vb.get(vo) else 128.toByte()
            out[pos++] = if (uo < ulim) ub.get(uo) else 128.toByte()
        }
    }

    // ------------------------------------------------------------------ ylläpito

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            tickCount++
            decideSegments()
            processClips()
            if (tickCount % 20 == 1) checkBattery()
            if (tickCount % 200 == 0) {
                Storage.cleanup(this@CameraService, Prefs.keepNights(this@CameraService), MIN_FREE)
                if (Storage.root(this@CameraService).usableSpace < 1_000_000_000L)
                    errorText = "Muisti lähes täynnä"
            }
            updateNotif()
            main.postDelayed(this, 3000)
        }
    }

    private fun checkBattery() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val i = (if (Build.VERSION.SDK_INT >= 33) registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
                 else registerReceiver(null, filter)) ?: return
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        batteryText = "Akku $level %, ${temp.toInt()} °C" + if (plugged) ", latauksessa" else ", EI LATAUKSESSA"
        if (!plugged && !unpluggedWarned) { unpluggedWarned = true; Telegram.text(this, "🔌 Laturi irti! Akku $level %") }
        if (plugged) unpluggedWarned = false
        if (temp >= 45f && !hotWarned) { hotWarned = true; Telegram.text(this, "🔥 Puhelin kuumenee: ${temp.toInt()} °C") }
        if (temp < 40f) hotWarned = false
        if (!plugged && level in 0..15) errorText = "Akku vähissä ($level %)"
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        errorText = msg
        Telegram.text(this, "⚠ $msg")
        updateNotif()
    }

    // ------------------------------------------------------------------ pysäytys

    fun beginStop() {
        if (!running) { stopSelf(); return }
        if (stopping) return
        stopping = true
        val r = activeRecording
        if (r != null) {
            r.stop()
            main.postDelayed({ finishStop() }, 8000)
        } else finishStop()
    }

    private fun finishStop() {
        if (finished) return
        finished = true
        val now = System.currentTimeMillis()
        if (activeEventStart != 0L) {
            val e = Ev(activeEventStart, maxOf(lastMotionAt, activeEventStart), lastScore, "")
            recentEvents.add(longArrayOf(e.start, e.end))
            Storage.appendEvent(this, e)
        }
        for (s in pendingSegs) if (overlapsEvent(s.start, s.end)) keep(s) else s.file.delete()
        pendingSegs.clear()
        activeEventStart = 0L
        Storage.appendSession(this, "E", now)
        Telegram.text(this, "⏹ Valvonta pysäytetty. Tapahtumia: $eventsCount")
        running = false
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        try { activeRecording?.stop() } catch (_: Throwable) {}
        try { camera?.cameraControl?.enableTorch(false) } catch (_: Throwable) {}
        try { provider?.unbindAll() } catch (_: Throwable) {}
        analysisExec.shutdown()
        Telegram.stopPolling()
        try { wakeLock?.release() } catch (_: Throwable) {}
        try { wifiLock?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
