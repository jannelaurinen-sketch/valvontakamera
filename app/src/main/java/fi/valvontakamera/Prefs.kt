package fi.valvontakamera

import android.content.Context
import android.content.SharedPreferences

/** Sovelluksen asetukset. */
object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("valvonta", Context.MODE_PRIVATE)

    /** Liikeherkkyys 1 (vähiten herkkä) … 10 (herkin). */
    fun sensitivity(c: Context) = sp(c).getInt("sens", 6)
    fun setSensitivity(c: Context, v: Int) = sp(c).edit().putInt("sens", v).apply()

    /** "FHD" = 1080p tai "HD" = 720p. */
    fun quality(c: Context) = sp(c).getString("quality", "FHD") ?: "FHD"
    fun setQuality(c: Context, v: String) = sp(c).edit().putString("quality", v).apply()

    fun audio(c: Context) = sp(c).getBoolean("audio", true)
    fun setAudio(c: Context, v: Boolean) = sp(c).edit().putBoolean("audio", v).apply()

    /** Puhelimen oma valo (taskulamppu) päällä valvonnan aikana. */
    fun torch(c: Context) = sp(c).getBoolean("torch", false)
    fun setTorch(c: Context, v: Boolean) = sp(c).edit().putBoolean("torch", v).apply()

    fun tgEnabled(c: Context) = sp(c).getBoolean("tg_on", true)
    fun setTgEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("tg_on", v).apply()
    fun tgToken(c: Context) = sp(c).getString("tg_token", "")!!.trim()
    fun setTgToken(c: Context, v: String) = sp(c).edit().putString("tg_token", v.trim()).apply()
    fun tgChat(c: Context) = sp(c).getString("tg_chat", "")!!.trim()
    fun setTgChat(c: Context, v: String) = sp(c).edit().putString("tg_chat", v.trim()).apply()

    /** Kuinka monen yön nauhoitukset säilytetään. */
    fun keepNights(c: Context) = sp(c).getInt("keep", 7)
    fun setKeepNights(c: Context, v: Int) = sp(c).edit().putInt("keep", v).apply()
}
