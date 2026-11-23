package com.rootclicker.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 柔和的彩色光斑背景，让半透明白色“毛玻璃”卡片有东西可透。 */
class AuroraDrawable : Drawable() {
    private val basePaint = Paint().apply { color = 0xFFF3F5FA.toInt() }
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shaders: List<Shader> = emptyList()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) {
            shaders = emptyList()
            return
        }
        fun blob(cx: Float, cy: Float, r: Float, color: Int): Shader =
            RadialGradient(
                bounds.left + cx, bounds.top + cy, r,
                color, color and 0x00FFFFFF, Shader.TileMode.CLAMP
            )
        shaders = listOf(
            blob(w * 0.08f, h * 0.05f, w * 0.95f, 0x99A9CBFF.toInt()),
            blob(w * 0.98f, h * 0.28f, w * 0.75f, 0x80D9C2FF.toInt()),
            blob(w * 0.05f, h * 0.78f, w * 0.85f, 0x80FFCBDD.toInt()),
            blob(w * 0.95f, h * 0.98f, w * 0.80f, 0x80B9EFE0.toInt())
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, basePaint)
        for (s in shaders) {
            blobPaint.shader = s
            canvas.drawRect(bounds, blobPaint)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

object AppleGlassUi {
    const val GLASS_PANEL = 0xF2FFFFFF.toInt()
    const val HAIRLINE = 0x16000000
    const val RIM = 0xCCFFFFFF.toInt()

    const val TEXT_PRIMARY = 0xFF1C1C1E.toInt()
    const val TEXT_SECONDARY = 0xFF6C6C70.toInt()
    const val TEXT_TERTIARY = 0xFF9A9AA1.toInt()

    const val ACCENT_BLUE = 0xFF007AFF.toInt()
    const val ACCENT_GREEN = 0xFF34C759.toInt()
    const val ACCENT_RED = 0xFFFF3B30.toInt()
    const val ACCENT_AMBER = 0xFFFF9500.toInt()

    const val TINT_BLUE = 0x1F007AFF
    const val TINT_GREEN = 0x2434C759
    const val TINT_RED = 0x1FFF3B30
    const val TINT_GRAY = 0x14000000
    const val FIELD_BG = 0x0F000000

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun glass(
        ctx: Context,
        fill: Int,
        stroke: Int = HAIRLINE,
        radiusDp: Float = 20f,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        setColor(fill)
        if (strokeDp > 0) setStroke(dp(ctx, strokeDp), stroke)
    }

    private fun maskRect(ctx: Context, radiusDp: Float): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        setColor(Color.WHITE)
    }

    private fun maskOval(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
    }

    /** 圆角按钮底：填充色 + 按压涟漪。 */
    fun roundBg(ctx: Context, fill: Int, radiusDp: Float, stroke: Int = 0, ripple: Int = 0x22000000): Drawable {
        val base = glass(ctx, fill, stroke, radiusDp, if (stroke != 0) 1 else 0)
        return RippleDrawable(ColorStateList.valueOf(ripple), base, maskRect(ctx, radiusDp))
    }

    fun circleBg(ctx: Context, fill: Int, stroke: Int = 0, ripple: Int = 0x22000000): Drawable {
        val base = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != 0) setStroke(dp(ctx, 1), stroke)
        }
        return RippleDrawable(ColorStateList.valueOf(ripple), base, maskOval())
    }

    /** 磨砂白卡片（带轻微上下渐变和柔和投影）。 */
    fun card(ctx: Context, radiusDp: Float = 22f): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xE6FFFFFF.toInt(), 0xB3FFFFFF.toInt())
        ).apply {
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
            setStroke(dp(ctx, 1), RIM)
        }
        val pad = dp(ctx, 16)
        setPadding(pad, pad, pad, pad)
        elevation = dp(ctx, 2).toFloat()
    }

    fun circleButton(
        ctx: Context,
        label: String,
        sizeDp: Int = 40,
        textSp: Float = 12f,
        fill: Int = TINT_GRAY,
        fg: Int = TEXT_PRIMARY,
        stroke: Int = 0,
        onClick: () -> Unit
    ): TextView {
        val px = dp(ctx, sizeDp)
        return TextView(ctx).apply {
            text = label
            textSize = textSp
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(fg)
            layoutParams = LinearLayout.LayoutParams(px, px)
            background = circleBg(ctx, fill, stroke)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun pillButton(
        ctx: Context,
        label: String,
        fill: Int,
        fg: Int,
        textSp: Float = 15f,
        radiusDp: Float = 14f,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = label
        textSize = textSp
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(fg)
        background = roundBg(ctx, fill, radiusDp)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun fieldBg(ctx: Context): Drawable = glass(ctx, FIELD_BG, 0, 12f, 0)

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(HAIRLINE)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(ctx, 1).coerceAtLeast(1)
        )
    }

    /**
     * 手指一按下就执行（不等抬起）。
     * 连点期间系统注入的手势会打断用户触摸，导致普通 onClick 收不到“抬起”，
     * 所以“停止”必须用 DOWN 事件触发，才能保证每次都能停下来。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun onPressDown(v: View, action: () -> Unit) {
        v.isClickable = true
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    action()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    true
                }
                else -> true
            }
        }
        v.setOnClickListener { action() }
    }

    fun styleDialog(ctx: Context, d: Dialog) {
        d.window?.setBackgroundDrawable(glass(ctx, 0xF7FFFFFF.toInt(), HAIRLINE, 26f))
        d.window?.setDimAmount(0.22f)
    }

    fun blurDialog(d: Dialog) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                d.window?.setBackgroundBlurRadius(48)
            } catch (_: Exception) {
            }
        }
    }
}
