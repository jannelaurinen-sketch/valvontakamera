package fi.valvontakamera

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class NightsActivity : Activity() {
    private lateinit var list: ListView
    private var nights = listOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        col.add(label("Yön nauhoitukset", 22f, true))
        col.add(label("Napauta yötä avataksesi aikajanan. Pitkä painallus poistaa yön.", 13f, color = Color.GRAY))
        list = ListView(this)
        col.add(list, 0)
        (list.layoutParams as LinearLayout.LayoutParams).weight = 1f
        setContentView(col)
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, TimelineActivity::class.java).putExtra("night", nights[pos].name))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val d = nights[pos]
            AlertDialog.Builder(this).setTitle("Poistetaanko yö?")
                .setMessage(title(d.name) + "\nKaikki tämän yön videot ja kuvat poistetaan.")
                .setPositiveButton("Poista") { _, _ -> Storage.deleteNight(d); refresh() }
                .setNegativeButton("Peruuta", null).show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun title(name: String): String = try {
        val fi = Locale("fi")
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(name)!!
        val cal = Calendar.getInstance().apply { time = d }
        val a = SimpleDateFormat("EEEE d.M.", fi).format(cal.time)
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val b = SimpleDateFormat("EEEE d.M.yyyy", fi).format(cal.time)
        "$a – $b"
    } catch (_: Exception) { name }

    private fun refresh() {
        nights = Storage.nights(this)
        val items = nights.map { d ->
            val ev = Storage.readEvents(d).size
            val segs = Storage.readSegments(d).size
            "${title(d.name)}\n$ev tapahtumaa · $segs videopätkää · ${Fmt.size(Storage.dirSize(d))}"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            if (items.isEmpty()) listOf("Ei vielä nauhoituksia") else items)
        list.isEnabled = items.isNotEmpty()
    }
}
