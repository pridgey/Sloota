package dev.pridgey.sloota.gate

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.pridgey.sloota.R

/**
 * The full-screen "you did it" card, drawn over everything as an accessibility overlay
 * (no extra permission needed). It goes away on its own, or with a tap.
 *
 * The "Paper" look: a cream page, a small Sloota mark at the top, one big serif word,
 * a rule, the explanation, and a dark Okay button with a note that it closes itself.
 */
class EndOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val hide = Runnable { dismiss() }

    private val dp = service.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    /** Instrument Serif (OFL, see licenses/), falling back to the system serif if it won't load. */
    private val serifFace: Typeface by lazy {
        runCatching { service.resources.getFont(R.font.instrument_serif) }.getOrDefault(Typeface.SERIF)
    }

    fun show(title: String, body: String, holdMs: Long = 4500) {
        dismiss()
        val page = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(36), px(64), px(36), px(48))
            setBackgroundColor(PAPER)

            addView(header())
            addView(View(service), LinearLayout.LayoutParams(0, 0, 1f))
            addView(text(title, 96f, INK, serif = true).apply {
                setLineSpacing(0f, 0.95f)
                letterSpacing = -0.02f
            })
            addView(View(service).apply { setBackgroundColor(INK); alpha = 0.2f }, block(1, top = 18))
            addView(text(body, 18f, BODY).apply { setLineSpacing(0f, 1.55f) }, block(LinearLayout.LayoutParams.WRAP_CONTENT, top = 18))
            addView(View(service), LinearLayout.LayoutParams(0, 0, 1f))
            addView(okay(), block(px(54)))
            addView(
                text("Closing on its own in a few seconds", 13f, MUTED).apply { gravity = Gravity.CENTER },
                block(LinearLayout.LayoutParams.WRAP_CONTENT, top = 14),
            )
            setOnClickListener { dismiss() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(page, params) }.onSuccess {
            view = page
            page.alpha = 0f
            page.animate().alpha(1f).setDuration(180).start()
            main.postDelayed(hide, holdMs)
        }
    }

    fun dismiss() {
        main.removeCallbacks(hide)
        val v = view ?: return
        view = null
        runCatching { wm.removeView(v) }
    }

    /** The small orange dot and "Sloota" in the top corner. */
    private fun header() = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            View(service).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DOT) } },
            LinearLayout.LayoutParams(px(12), px(12)),
        )
        addView(
            text("Sloota", 14f, LABEL).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = px(10) },
        )
    }

    private fun okay() = TextView(service).apply {
        text = "Okay"
        setTextColor(PAPER)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = "Okay"
        background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor(INK)
        }
        setOnClickListener { dismiss() }
    }

    /** Full-width row params: height in px (or WRAP_CONTENT), top gap in dp. */
    private fun block(height: Int, top: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { topMargin = px(top) }

    private fun text(s: String, sp: Float, color: Int, serif: Boolean = false) = TextView(service).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (serif) typeface = serifFace
    }

    private companion object {
        val PAPER = Color.rgb(0xFB, 0xF7, 0xF2)
        val INK = Color.rgb(0x1F, 0x2A, 0x24)
        val BODY = Color.rgb(0x3A, 0x44, 0x3E)
        val LABEL = Color.rgb(0x4A, 0x54, 0x4E)
        val MUTED = Color.rgb(0x5C, 0x66, 0x60)
        val DOT = Color.rgb(0xE0, 0x97, 0x4A)
    }
}
