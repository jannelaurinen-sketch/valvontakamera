package fi.valvontakamera

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Pienet apufunktiot käyttöliittymän rakentamiseen koodissa. */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.label(text: String, size: Float = 15f, bold: Boolean = false, color: Int = Color.rgb(230, 230, 230)) =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

fun Context.header(text: String) = label(text, 18f, true, Color.rgb(79, 195, 247)).apply {
    setPadding(0, dp(18), 0, dp(4))
}

fun Context.button(text: String, onClick: (View) -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener(onClick)
}

fun Context.row(vararg views: View) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    for (v in views) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
}

fun LinearLayout.add(v: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
    addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    return v
}
