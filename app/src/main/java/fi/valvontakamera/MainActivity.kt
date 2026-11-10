package fi.valvontakamera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Surface
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.sqrt

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var preview: ImageView
    private lateinit var previewHint: TextView
    private lateinit var status: TextView
    private lateinit var meter: ProgressBar
    private lateinit var startBtn: android.widget.Button
    private lateinit var torchSw: Switch
    private lateinit var tokenEt: EditText
    private lateinit var chatEt: EditText
    private lateinit var batteryBtn: android.widget.Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }

        col.add(label("Valvontakamera", 24f, true))

        preview = ImageView(this).apply {
            setBackgroundColor(Color.rgb(20, 24, 30))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        col.add(preview, dp(220))
        previewHint = label("Kuva näkyy tässä kun valvonta on käynnissä. Keltainen = liike-ehdokas, punainen = havaittu liike.",
            13f, color = Color.GRAY)
        col.add(previewHint)

        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.add(meter)
        status = label("", 14f)
        col.add(status)

        startBtn = button("Käynnistä valvonta") { toggle() }
        col.add(startBtn, dp(64))

        torchSw = Switch(this).apply {
            text = "Puhelimen oma valo päällä"
            setTextColor(Color.WHITE)
            isChecked = Prefs.torch(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setTorch(this@MainActivity, on)
                CameraService.instance?.applyTorch(on)
            }
        }
        col.add(torchSw)

        col.add(button("Yön nauhoitukset ja aikajana") {
            startActivity(Intent(this, NightsActivity::class.java))
        })

        // ---------------- Liikeherkkyys
        col.add(header("Liikeherkkyys"))
        val sensLabel = label("")
        fun sensText(v: Int) = "Herkkyys $v / 10  " + when {
            v <= 3 -> "(vain isot kohteet, esim. auto)"
            v <= 7 -> "(ihmiset ja isot eläimet)"
            else -> "(pienetkin liikkeet)"
        }
        sensLabel.text = sensText(Prefs.sensitivity(this))
        col.add(sensLabel)
        col.add(SeekBar(this).apply {
            max = 9
            progress = Prefs.sensitivity(this@MainActivity) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = p + 1
                    sensLabel.text = sensText(v)
                    Prefs.setSensitivity(this@MainActivity, v)
                    CameraService.instance?.setSensitivity(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        col.add(label("Sadepisarat ja lumihiutaleet suodatetaan automaattisesti. Säädä herkkyyttä katsomalla " +
                "esikatselukuvaa: punaisella näkyvä liike käynnistää nauhoituksen.", 13f, color = Color.GRAY))

        // ---------------- Video
        col.add(header("Video"))
        val rg = RadioGroup(this)
        val rbF = RadioButton(this).apply { text = "Full HD 1080p"; setTextColor(Color.WHITE); id = 101 }
        val rbH = RadioButton(this).apply { text = "HD 720p (säästää muistia)"; setTextColor(Color.WHITE); id = 102 }
        rg.addView(rbF); rg.addView(rbH)
        rg.check(if (Prefs.quality(this) == "HD") 102 else 101)
        rg.setOnCheckedChangeListener { _, id -> Prefs.setQuality(this, if (id == 102) "HD" else "FHD") }
        col.add(rg)
        col.add(Switch(this).apply {
            text = "Nauhoita myös ääni"
            setTextColor(Color.WHITE)
            isChecked = Prefs.audio(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setAudio(this@MainActivity, on) }
        })
        val keepLabel = label("")
        fun keepText(v: Int) = "Säilytä nauhoitukset $v yöltä (vanhimmat poistetaan)"
        keepLabel.text = keepText(Prefs.keepNights(this))
        col.add(keepLabel)
        col.add(SeekBar(this).apply {
            max = 29
            progress = Prefs.keepNights(this@MainActivity) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    keepLabel.text = keepText(p + 1); Prefs.setKeepNights(this@MainActivity, p + 1)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        col.add(label("Laatu- ja ääniasetus tulevat voimaan seuraavalla käynnistyksellä.", 13f, color = Color.GRAY))

        // ---------------- Telegram
        col.add(header("Telegram"))
        col.add(Switch(this).apply {
            text = "Lähetä kuva ja videoklippi Telegramiin"
            setTextColor(Color.WHITE)
            isChecked = Prefs.tgEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setTgEnabled(this@MainActivity, on) }
        })
        col.add(label("1) Avaa Telegramissa @BotFather, lähetä /newbot ja anna botille nimi.\n" +
                "2) Kopioi saamasi token alle.\n" +
                "3) Lähetä omalle botillesi mikä tahansa viesti ja paina \"Hae chat ID\".", 13f, color = Color.GRAY))
        tokenEt = EditText(this).apply {
            hint = "Bot token (esim. 123456:ABC…)"
            setText(Prefs.tgToken(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        col.add(tokenEt)
        chatEt = EditText(this).apply {
            hint = "Chat ID"
            setText(Prefs.tgChat(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        col.add(chatEt)
        col.add(row(
            button("Hae chat ID") { findChat() },
            button("Testiviesti") { testTg() }
        ))

        // ---------------- Taustatoiminta
        col.add(header("Taustatoiminta"))
        batteryBtn = button("Salli toiminta taustalla (akunsäästö pois)") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        col.add(batteryBtn)
        col.add(label("Samsungilla kannattaa lisäksi: Asetukset → Akku → Taustakäytön rajoitukset → " +
                "lisää Valvontakamera kohtaan \"Ei koskaan lepotilaan asetettavat sovellukset\".", 13f, color = Color.GRAY))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        CameraService.previewListener = { bmp -> preview.setImageBitmap(bmp) }
        val pm = getSystemService(PowerManager::class.java)
        batteryBtn.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) android.view.View.GONE
                                else android.view.View.VISIBLE
        h.post(poll)
    }

    override fun onPause() {
        super.onPause()
        CameraService.previewListener = null
        h.removeCallbacks(poll)
        Prefs.setTgToken(this, tokenEt.text.toString())
        Prefs.setTgChat(this, chatEt.text.toString())
    }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 1000)
        }
    }

    private fun refresh() {
        val s = CameraService.instance
        val free = Storage.root(this).usableSpace
        if (s != null && s.running) {
            startBtn.text = "Pysäytä valvonta"
            meter.progress = (sqrt(s.lastScore.toDouble()) * 300).toInt().coerceIn(0, 100)
            val sb = StringBuilder()
            sb.append(if (s.eventActive) "● LIIKETTÄ – NAUHOITETAAN\n" else "● Valvonta käynnissä\n")
            if (s.resolutionText.isNotEmpty()) sb.append("Video ${s.resolutionText}\n")
            sb.append("Tapahtumia: ${s.eventsCount} · tallennettuja pätkiä: ${s.keptCount}\n")
            if (s.lastMotionAt > 0) sb.append("Viimeisin liike: ${Fmt.time(s.lastMotionAt)}\n")
            if (s.rainLevel > 0.01f) sb.append("Sadetta/lunta havaittu – suodatus tiukempi\n")
            if (s.batteryText.isNotEmpty()) sb.append(s.batteryText + "\n")
            if (s.errorText.isNotEmpty()) sb.append("⚠ ${s.errorText}\n")
            sb.append(tgStatus())
            sb.append("Vapaata muistia: ${Fmt.size(free)}")
            status.text = sb.toString()
            previewHint.visibility = android.view.View.GONE
        } else {
            startBtn.text = "Käynnistä valvonta"
            meter.progress = 0
            status.text = "Valvonta ei ole käynnissä.\n" + tgStatus() + "Vapaata muistia: ${Fmt.size(free)}"
            previewHint.visibility = android.view.View.VISIBLE
        }
    }

    private fun tgStatus(): String {
        if (!Prefs.tgEnabled(this)) return "Telegram: pois päältä\n"
        if (!Telegram.configured(this)) return "Telegram: token / chat ID puuttuu\n"
        val sb = StringBuilder("Telegram: ")
        sb.append(if (Telegram.queueSize > 0) "jonossa ${Telegram.queueSize}" else "ok")
        if (Telegram.lastError.isNotEmpty()) sb.append(" (${Telegram.lastError})")
        return sb.append("\n").toString()
    }

    private fun toggle() {
        val s = CameraService.instance
        if (s != null && s.running) {
            CameraService.stop(this)
            return
        }
        Prefs.setTgToken(this, tokenEt.text.toString())
        Prefs.setTgChat(this, chatEt.text.toString())
        val need = ArrayList<String>()
        need.add(Manifest.permission.CAMERA)
        if (Prefs.audio(this)) need.add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        else startService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startService()
        else Toast.makeText(this, "Kameran käyttölupa tarvitaan", Toast.LENGTH_LONG).show()
    }

    private fun startService() {
        val rot = display?.rotation ?: Surface.ROTATION_0
        CameraService.start(this, rot)
        Toast.makeText(this, "Valvonta käynnistyy. Näytön voi sammuttaa.", Toast.LENGTH_SHORT).show()
    }

    private fun findChat() {
        val token = tokenEt.text.toString().trim()
        if (token.isEmpty()) { Toast.makeText(this, "Syötä ensin token", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val res = try { Result.success(Telegram.findChats(token)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                res.onSuccess { chats ->
                    val labels = chats.map { "${it.second}\n${it.first}" }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Valitse mihin hälytykset lähetetään")
                        .setItems(labels) { _, i ->
                            val id = chats[i].first
                            chatEt.setText(id)
                            Prefs.setTgToken(this, token); Prefs.setTgChat(this, id)
                            Toast.makeText(this, "Valittu: ${chats[i].second}", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Peruuta", null).show()
                }.onFailure {
                    AlertDialog.Builder(this).setTitle("Chat ID:tä ei löytynyt")
                        .setMessage(it.message).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun testTg() {
        val token = tokenEt.text.toString().trim()
        val chat = chatEt.text.toString().trim()
        Prefs.setTgToken(this, token); Prefs.setTgChat(this, chat)
        Thread {
            val err = try { Telegram.sendNow(token, chat, "✅ Valvontakamera: testiviesti") } catch (e: Exception) {
                "Yhteysvirhe: ${e.message}\n\nTarkista että puhelin on Wi-Fi-verkossa."
            }
            runOnUiThread {
                when {
                    err == null -> Toast.makeText(this, "Testiviesti lähetetty", Toast.LENGTH_LONG).show()
                    err.startsWith("MIGRATE:") -> {
                        val id = err.removePrefix("MIGRATE:")
                        chatEt.setText(id); Prefs.setTgChat(this, id)
                        Toast.makeText(this, "Ryhmän numero oli vaihtunut – korjattu. Paina Testiviesti uudelleen.",
                            Toast.LENGTH_LONG).show()
                    }
                    else -> AlertDialog.Builder(this).setTitle("Testiviesti epäonnistui")
                        .setMessage(err).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }
}
