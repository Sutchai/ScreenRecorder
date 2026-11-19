package com.screenrec

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * แถบลอย: หุบ = วงกลม/แคปซูลเล็ก (จุดสถานะ + เวลา)
 * แตะ = ขยายเป็น Pause/Resume + Stop, หุบเองใน 3 วินาที, ลากได้ ดูดติดขอบซ้าย/ขวา
 */
class FloatingControl(
    private val ctx: Context,
    private val onPauseResume: () -> Unit,
    private val onStop: () -> Unit,
    private val elapsedMs: () -> Long,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var root: LinearLayout? = null
    private lateinit var dot: TextView
    private lateinit var timeText: TextView
    private lateinit var pauseBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private var blink: ObjectAnimator? = null

    private var paused = false
    private var expanded = false
    private var snapRight = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    private val collapseRunnable = Runnable { setExpanded(false) }
    private val tick = object : Runnable {
        override fun run() {
            timeText.text = format(elapsedMs())
            handler.postDelayed(this, 500)
        }
    }

    private fun format(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun button(text: String, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        minHeight = dp(40)
        visibility = View.GONE
        setOnClickListener {
            onClick()
            scheduleCollapse()
        }
    }

    fun show() {
        if (root != null) return
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#D9202020"))
            cornerRadius = dp(24).toFloat()
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        dot = TextView(ctx).apply {
            text = "●"; textSize = 16f; setTextColor(Color.RED)
        }
        timeText = TextView(ctx).apply {
            textSize = 12f; setTextColor(Color.WHITE); setPadding(dp(6), 0, 0, 0)
            text = format(elapsedMs())
        }
        head.addView(dot); head.addView(timeText)

        pauseBtn = button("⏸") { onPauseResume() }
        stopBtn = button("⏹") { onStop() }

        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            addView(head); addView(pauseBtn); addView(stopBtn)
        }
        root = r

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(160)
        }

        var startX = 0; var startY = 0
        var downX = 0f; var downY = 0f
        var moved = false
        head.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    downX = e.rawX; downY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(r, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) snapToEdge() else setExpanded(!expanded)
                    true
                }
                else -> false
            }
        }

        wm.addView(r, lp)
        setPaused(false)
        handler.post(tick)
    }

    private fun screenWidth() = ctx.resources.displayMetrics.widthPixels

    private fun snapToEdge() {
        val r = root ?: return
        snapRight = lp.x + r.width / 2 > screenWidth() / 2
        lp.x = if (snapRight) screenWidth() - r.width else 0
        wm.updateViewLayout(r, lp)
    }

    private fun setExpanded(e: Boolean) {
        val r = root ?: return
        expanded = e
        val vis = if (e) View.VISIBLE else View.GONE
        pauseBtn.visibility = vis
        stopBtn.visibility = vis
        handler.removeCallbacks(collapseRunnable)
        if (e) scheduleCollapse()
        r.post {
            if (snapRight) {
                lp.x = screenWidth() - r.width
                runCatching { wm.updateViewLayout(r, lp) }
            }
        }
    }

    private fun scheduleCollapse() {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, 3000)
    }

    fun setPaused(p: Boolean) {
        if (root == null) return
        paused = p
        blink?.cancel()
        if (p) {
            dot.setTextColor(Color.YELLOW)
            dot.alpha = 1f
            pauseBtn.text = "▶"
        } else {
            dot.setTextColor(Color.RED)
            pauseBtn.text = "⏸"
            blink = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        blink?.cancel(); blink = null
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }
}
