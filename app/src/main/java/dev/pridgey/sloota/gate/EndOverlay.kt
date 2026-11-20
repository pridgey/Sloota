package dev.pridgey.sloota.gate

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.pridgey.sloota.R

/**
 * The full-screen "you did it" card, drawn over everything as an accessibility overlay
 * (no extra permission needed). It covers the app until Okay is tapped or the user navigates away
 * (the service calls [dismiss] when another app comes to the front). Taps elsewhere do nothing.
 * While it's up it holds audio focus, so whatever the app was playing stops underneath.
 *
 * The "Paper" look: a cream page, a small Sloota mark at the top, one big serif word,
 * a rule, the explanation, and a dark Okay button.
 */
class EndOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    private val dp = service.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    /** Instrument Serif (OFL, see licenses/), falling back to the system serif if it won't load. */
    private val serifFace: Typeface by lazy {
        runCatching { service.resources.getFont(R.font.instrument_serif) }.getOrDefault(Typeface.SERIF)
    }

    /** [onOkay] runs after the Okay button dismisses the screen. */
    fun show(title: String, body: String, onOkay: () -> Unit) {
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
            addView(okay(onOkay), block(px(54)))
            // Swallow taps so they don't reach the app underneath; only Okay dismisses.
            isClickable = true
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
            runCatching { audio.requestAudioFocus(focus) }
        }
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        runCatching { wm.removeView(v) }
        runCatching { audio.abandonAudioFocusRequest(focus) }
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

    private fun okay(onOkay: () -> Unit) = TextView(service).apply {
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
        setOnClickListener {
            dismiss()
            onOkay()
        }
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
        val DOT = Color.rgb(0xE0, 0x97, 0x4A)
    }
}
