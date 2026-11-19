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

/**
 * The full-screen "you did it" card, drawn over everything as an accessibility overlay
 * (no extra permission needed). It goes away on its own, or with a tap.
 */
class EndOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val hide = Runnable { dismiss() }

    fun show(title: String, body: String, holdMs: Long = 4500) {
        dismiss()
        val dp = service.resources.displayMetrics.density
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
            setBackgroundColor(Color.argb(242, 14, 18, 22))
            addView(text(title, 30f, bold = true, color = Color.WHITE))
            addView(View(service), LinearLayout.LayoutParams(1, (12 * dp).toInt()))
            addView(text(body, 17f, bold = false, color = Color.argb(220, 255, 255, 255)))
            addView(View(service), LinearLayout.LayoutParams(1, (36 * dp).toInt()))
            addView(
                text("Okay", 16f, bold = true, color = Color.rgb(14, 18, 22)).apply {
                    setPadding((28 * dp).toInt(), (12 * dp).toInt(), (28 * dp).toInt(), (12 * dp).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 100 * dp
                        setColor(Color.rgb(183, 230, 207))
                    }
                    setOnClickListener { dismiss() }
                },
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
        runCatching { wm.addView(card, params) }.onSuccess {
            view = card
            card.alpha = 0f
            card.animate().alpha(1f).setDuration(180).start()
            main.postDelayed(hide, holdMs)
        }
    }

    fun dismiss() {
        main.removeCallbacks(hide)
        val v = view ?: return
        view = null
        runCatching { wm.removeView(v) }
    }

    private fun text(s: String, sp: Float, bold: Boolean, color: Int) = TextView(service).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
}
