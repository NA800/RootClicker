package com.rootclicker.app

import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class ClickPoint(
    var x: Int,
    var y: Int,
    var count: Int = 1,
    var intervalMs: Long = 50L,
    var tapDurationMs: Long = 20L,
    var longPress: Boolean = false,
    var holdMs: Long = 1000L,
    var offsetPx: Int = 0
) {
    fun toSpec(): PointSpec = PointSpec(
        x = x,
        y = y,
        count = count,
        intervalMs = intervalMs,
        tapDurationMs = tapDurationMs,
        longPress = longPress,
        holdMs = holdMs,
        offsetPx = offsetPx
    )

    companion object {
        fun fromSpec(s: PointSpec): ClickPoint = ClickPoint(
            x = s.x,
            y = s.y,
            count = s.count,
            intervalMs = s.intervalMs,
            tapDurationMs = s.tapDurationMs,
            longPress = s.longPress,
            holdMs = s.holdMs,
            offsetPx = s.offsetPx
        )
    }
}

private class Marker(val view: TextView, val params: WindowManager.LayoutParams, val point: ClickPoint)
private class ClickTarget(val p: ClickPoint, val cx: Int, val cy: Int)

/** 一次待执行的动作。stride = 到下一次动作起点的间隔（已保证 > 按住时长）。 */
private class Action(val x: Int, val y: Int, val dur: Long, val stride: Long, val isLong: Boolean)

/** 按“点击点 → 次数 → 间隔”的顺序源源不断地产出动作。 */
private class ActionSource(
    private val targets: List<ClickTarget>,
    private val loop: Boolean,
    private val sw: Int,
    private val sh: Int
) {
    private var ti = 0
    private var done = 0

    var isFinished = false
        private set

    fun next(): Action? {
        if (isFinished) return null
        while (true) {
            if (ti >= targets.size) {
                if (loop && targets.isNotEmpty()) {
                    ti = 0
                    done = 0
                } else {
                    isFinished = true
                    return null
                }
            }
            val t = targets[ti]
            val p = t.p
            if (p.count != 0 && done >= p.count) {
                ti++
                done = 0
                continue
            }
            done++
            if (!loop && p.count != 0 && done >= p.count && ti == targets.lastIndex) {
                isFinished = true
            }
            val dur = if (p.longPress) p.holdMs.coerceIn(50L, 30000L) else p.tapDurationMs.coerceIn(5L, 5000L)
            val stride = max(p.intervalMs, dur + 2L)
            val (x, y) = jitter(t)
            return Action(x, y, dur, stride, p.longPress)
        }
    }

    private fun jitter(t: ClickTarget): Pair<Int, Int> {
        val r = t.p.offsetPx
        if (r <= 0) return t.cx to t.cy
        val ang = Random.nextDouble(0.0, 2.0 * PI)
        val dist = r * sqrt(Random.nextDouble())
        val x = (t.cx + dist * cos(ang)).roundToInt().coerceIn(0, max(0, sw - 1))
        val y = (t.cy + dist * sin(ang)).roundToInt().coerceIn(0, max(0, sh - 1))
        return x to y
    }
}

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var panel: View? = null
    private var headTitle: TextView? = null
    private var modeChip: TextView? = null
    private var presetChip: TextView? = null
    private var stopBtn: TextView? = null
    private var normalRow: View? = null
    private var runRow: View? = null
    private var countText: TextView? = null
    private var rateText: TextView? = null

    private val markers = mutableListOf<Marker>()
    private val points = mutableListOf<ClickPoint>()

    @Volatile
    private var runner: Job? = null
    private val running = AtomicBoolean(false)
    private val clickCount = AtomicLong(0L)
    private var runStartMs = 0L

    private var rootProcess: java.lang.Process? = null

    @Volatile
    private var rootOut: DataOutputStream? = null

    @Volatile
    private var rootIn: BufferedReader? = null

    @Volatile
    private var rootFast = false

    companion object {
        const val EXTRA_LOAD_PRESET = "extra_load_preset"
        const val ACTION_STOP = "com.rootclicker.app.ACTION_STOP"
        private const val CHANNEL_ID = "clicker_channel"
        private const val DONE = "__CF_DONE__"
        private const val BATCH_SPAN_MS = 300L
    }

    private fun dp(v: Int): Int = AppleGlassUi.dp(this, v)

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(7, buildNotification())
        showPanel()
        autoLoadLastPresetIfAny()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopClicks()
        }
        intent?.getStringExtra(EXTRA_LOAD_PRESET)?.let { name ->
            loadPresetByName(name)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags
        )
        val openPi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), flags)
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "停止点击", stopPi
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("点击器已就绪")
            .setContentText("悬浮面板运行中 · 可在此一键停止点击")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(stopAction)
            .build()
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun lp(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w,
            h,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun toast(msg: String, long: Boolean = false) {
        ui.post { Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }

    private fun vibrateLight() {
        if (!PresetManager.isHapticEnabled(this)) return
        ui.post {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else {
                    @Suppress("DEPRECATION")
                    val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    v.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (_: Exception) {
            }
        }
    }

    // ───────────────────────── 白色毛玻璃控制面板 ─────────────────────────

    private fun showPanel() {
        val ctx = this
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = AppleGlassUi.glass(ctx, AppleGlassUi.GLASS_PANEL, AppleGlassUi.HAIRLINE, 26f, 1)
            setPadding(dp(12), dp(6), dp(12), dp(12))
        }

        // 顶部：拖动条 + 标题 + 模式 / 方案胶囊（整块区域可拖动）
        val dragArea = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val grabber = View(ctx).apply {
            background = AppleGlassUi.glass(ctx, 0x26000000, 0, 3f, 0)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            }
        }
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(8))
        }

        headTitle = TextView(ctx).apply {
            text = "点击器"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(AppleGlassUi.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val currentMode = PresetManager.getMode(ctx)
        modeChip = TextView(ctx).apply {
            text = currentMode.shortTag
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (currentMode == ExecMode.ROOT) AppleGlassUi.ACCENT_AMBER else AppleGlassUi.ACCENT_BLUE)
            background = AppleGlassUi.roundBg(ctx, AppleGlassUi.TINT_GRAY, 11f)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            setOnClickListener { toggleExecMode() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }

        val lastPreset = PresetManager.getLastPresetName(ctx)
        presetChip = TextView(ctx).apply {
            text = if (lastPreset.isNullOrBlank()) "方案" else lastPreset
            textSize = 11f
            maxLines = 1
            maxWidth = dp(84)
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(AppleGlassUi.TEXT_PRIMARY)
            background = AppleGlassUi.roundBg(ctx, AppleGlassUi.TINT_GRAY, 11f)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            setOnClickListener { showPresetDialog() }
        }

        topRow.addView(headTitle)
        topRow.addView(modeChip)
        topRow.addView(presetChip)
        dragArea.addView(grabber)
        dragArea.addView(topRow)
        card.addView(dragArea)

        // 常态按钮行：增加 / 开始 / 停止 / 保存 / ×
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun circle(label: String, fill: Int, fg: Int, last: Boolean = false, onClick: () -> Unit): TextView =
            AppleGlassUi.circleButton(ctx, label, 42, 12f, fill, fg, 0, onClick).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    if (!last) marginEnd = dp(8)
                }
            }

        val addBtn = circle("增加", AppleGlassUi.TINT_BLUE, AppleGlassUi.ACCENT_BLUE) { addPoint() }
        val startBtn = circle("开始", AppleGlassUi.ACCENT_GREEN, Color.WHITE) { startClicks() }
        stopBtn = circle("停止", AppleGlassUi.TINT_RED, AppleGlassUi.ACCENT_RED) { stopClicks() }.apply { alpha = 0.5f }
        val saveBtn = circle("保存", AppleGlassUi.TINT_GRAY, AppleGlassUi.TEXT_PRIMARY) { promptSaveCurrentPreset() }
        val closeBtn = circle("×", AppleGlassUi.TINT_GRAY, AppleGlassUi.TEXT_SECONDARY, last = true) { stopSelf() }
            .apply { textSize = 18f }

        row.addView(addBtn)
        row.addView(startBtn)
        row.addView(stopBtn)
        row.addView(saveBtn)
        row.addView(closeBtn)
        normalRow = row
        card.addView(row)

        // 运行时：只显示实时统计 + 一个大号“停止”键（目标大、按下即停）
        val run = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val stats = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(120)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(10) }
        }
        countText = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(AppleGlassUi.TEXT_PRIMARY)
            text = "已点击 0 次"
        }
        rateText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(AppleGlassUi.TEXT_SECONDARY)
            text = "准备中…"
        }
        stats.addView(countText)
        stats.addView(rateText)
        val bigStop = TextView(ctx).apply {
            text = "停止"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = AppleGlassUi.roundBg(ctx, AppleGlassUi.ACCENT_RED, 23f, 0, 0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(48))
        }
        AppleGlassUi.onPressDown(bigStop) { stopClicks() }
        run.addView(stats)
        run.addView(bigStop)
        runRow = run
        card.addView(run)

        val p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        p.x = dp(12)
        p.y = dp(90)

        dragArea.setOnTouchListener(dragListener(card, p))
        panel = card
        wm.addView(card, p)
    }

    private fun dragListener(target: View, params: WindowManager.LayoutParams) = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.tag = floatArrayOf(e.rawX, e.rawY, params.x.toFloat(), params.y.toFloat())
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val a = v.tag as? FloatArray
                if (a != null) {
                    params.x = (a[2] + e.rawX - a[0]).toInt()
                    params.y = (a[3] + e.rawY - a[1]).toInt()
                    try {
                        wm.updateViewLayout(target, params)
                    } catch (_: Exception) {
                    }
                }
                true
            }
            else -> true
        }
    }

    private fun toggleExecMode() {
        if (running.get()) {
            toast("请先停止当前点击任务再切换模式")
            return
        }
        val current = PresetManager.getMode(this)
        val next = if (current == ExecMode.ACCESSIBILITY) ExecMode.ROOT else ExecMode.ACCESSIBILITY
        if (next == ExecMode.ACCESSIBILITY && !ClickAccessibilityService.isEnabled()) {
            toast("无障碍服务尚未开启，请先在主界面开启无障碍服务", true)
            return
        }
        PresetManager.setMode(this, next)
        vibrateLight()
        updateModeChipUi(next)
        toast("已切换为${next.label}")
    }

    private fun updateModeChipUi(mode: ExecMode) {
        modeChip?.text = mode.shortTag
        modeChip?.setTextColor(if (mode == ExecMode.ROOT) AppleGlassUi.ACCENT_AMBER else AppleGlassUi.ACCENT_BLUE)
    }

    // ───────────────────────── 位置方案管理 ─────────────────────────

    private fun autoLoadLastPresetIfAny() {
        val last = PresetManager.getLastPresetName(this) ?: return
        val preset = PresetManager.loadPresets(this).firstOrNull { it.name == last } ?: return
        if (points.isEmpty() && preset.points.isNotEmpty()) {
            applyPresetPoints(preset)
        }
    }

    private fun loadPresetByName(name: String) {
        val preset = PresetManager.loadPresets(this).firstOrNull { it.name == name } ?: return
        PresetManager.setLastPresetName(this, preset.name)
        presetChip?.text = preset.name
        if (!running.get()) {
            applyPresetPoints(preset)
        }
    }

    private fun applyPresetPoints(preset: ClickPreset) {
        clearAllMarkers()
        for (spec in preset.points) {
            val pt = ClickPoint.fromSpec(spec)
            points.add(pt)
            addMarker(pt)
        }
        presetChip?.text = preset.name
    }

    private fun clearAllMarkers() {
        for (m in markers) {
            try {
                wm.removeView(m.view)
            } catch (_: Exception) {
            }
        }
        markers.clear()
        points.clear()
    }

    private fun lightDialogCtx(): ContextThemeWrapper =
        ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)

    private fun showDialog(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        dialog.window?.setType(overlayType())
        AppleGlassUi.styleDialog(this, dialog)
        dialog.show()
        AppleGlassUi.blurDialog(dialog)
    }

    private fun promptSaveCurrentPreset() {
        if (points.isEmpty()) {
            toast("请先点“增加”添加至少一个点击点")
            return
        }
        val themed = lightDialogCtx()
        val input = EditText(themed).apply {
            setText(PresetManager.getLastPresetName(this@OverlayService)
                ?: "方案 ${PresetManager.loadPresets(this@OverlayService).size + 1}")
            setSelectAllOnFocus(true)
            setSingleLine(true)
        }
        val box = LinearLayout(themed).apply {
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        showDialog(
            AlertDialog.Builder(themed)
                .setTitle("保存点击方案（${points.size} 个点）")
                .setView(box)
                .setPositiveButton("保存") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) {
                        toast("方案名称不能为空")
                        return@setPositiveButton
                    }
                    val specs = snapshotCurrentPointsWithCoords().map { it.toSpec() }
                    PresetManager.savePreset(this, name, specs)
                    presetChip?.text = name
                    vibrateLight()
                    toast("已保存方案：$name")
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun showPresetDialog() {
        val presets = PresetManager.loadPresets(this)
        val themed = lightDialogCtx()
        if (presets.isEmpty()) {
            showDialog(
                AlertDialog.Builder(themed)
                    .setTitle("点击方案")
                    .setMessage("还没有保存任何方案。摆好点击点后，点“保存”即可存为方案。")
                    .setPositiveButton("知道了", null)
            )
            return
        }
        val names = presets.map { "${it.name}（${it.points.size} 点）" }.toTypedArray()
        showDialog(
            AlertDialog.Builder(themed)
                .setTitle("选择点击方案")
                .setItems(names) { _, which ->
                    val chosen = presets[which]
                    PresetManager.setLastPresetName(this, chosen.name)
                    if (running.get()) {
                        toast("请先停止再载入方案")
                    } else {
                        applyPresetPoints(chosen)
                        vibrateLight()
                        toast("已载入方案：${chosen.name}")
                    }
                }
                .setNeutralButton("保存当前") { _, _ -> promptSaveCurrentPreset() }
                .setNegativeButton("关闭", null)
        )
    }

    private fun snapshotCurrentPointsWithCoords(): List<ClickPoint> {
        return markers.map { m ->
            val loc = IntArray(2)
            m.view.getLocationOnScreen(loc)
            ClickPoint(
                x = loc[0],
                y = loc[1],
                count = m.point.count,
                intervalMs = m.point.intervalMs,
                tapDurationMs = m.point.tapDurationMs,
                longPress = m.point.longPress,
                holdMs = m.point.holdMs,
                offsetPx = m.point.offsetPx
            )
        }
    }

    // ───────────────────────── 点击点标记（长按设置） ─────────────────────────

    private fun addPoint() {
        if (running.get()) return
        val (w, h) = screenSize()
        val stagger = (points.size % 8) * dp(14)
        val pt = ClickPoint(
            x = (w / 2 - dp(17) + stagger).coerceIn(0, max(0, w - dp(34))),
            y = (h / 2 - dp(17) + stagger).coerceIn(0, max(0, h - dp(34))),
            count = 1,
            intervalMs = PresetManager.getDefaultIntervalMs(this),
            tapDurationMs = PresetManager.getDefaultTapMs(this),
            longPress = false,
            holdMs = 1000L,
            offsetPx = PresetManager.getDefaultOffset(this)
        )
        points.add(pt)
        addMarker(pt)
        vibrateLight()
    }

    private fun addMarker(point: ClickPoint) {
        val diameter = dp(34)
        val v = TextView(this).apply {
            text = (points.indexOf(point) + 1).toString()
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6007AFF.toInt())
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
        }
        val p = lp(diameter, diameter).apply {
            x = point.x
            y = point.y
        }

        var moved = false
        val longTask = Runnable {
            if (!moved) {
                vibrateLight()
                editPoint(point)
            }
        }

        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.tag = floatArrayOf(e.rawX, e.rawY, p.x.toFloat(), p.y.toFloat())
                    moved = false
                    ui.postDelayed(longTask, 450L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val a = view.tag as? FloatArray
                    if (a != null) {
                        if (!moved && hypot(e.rawX - a[0], e.rawY - a[1]) > dp(7)) {
                            moved = true
                            ui.removeCallbacks(longTask)
                        }
                        if (moved) {
                            p.x = (a[2] + e.rawX - a[0]).toInt()
                            p.y = (a[3] + e.rawY - a[1]).toInt()
                            point.x = p.x
                            point.y = p.y
                            try {
                                wm.updateViewLayout(view, p)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longTask)
                    true
                }
                else -> false
            }
        }

        wm.addView(v, p)
        markers.add(Marker(v, p, point))
    }

    private fun removePoint(point: ClickPoint) {
        val m = markers.firstOrNull { it.point === point }
        if (m != null) {
            try {
                wm.removeView(m.view)
            } catch (_: Exception) {
            }
            markers.remove(m)
        }
        points.remove(point)
        markers.forEachIndexed { i, mk -> mk.view.text = (i + 1).toString() }
    }

    private fun setMarkersLocked(locked: Boolean) {
        for (m in markers) {
            if (locked) {
                m.params.flags = m.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                m.params.alpha = 0.45f
            } else {
                m.params.flags = m.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                m.params.alpha = 1f
            }
            try {
                wm.updateViewLayout(m.view, m.params)
            } catch (_: Exception) {
            }
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val n = clickCount.get()
            val sec = (SystemClock.elapsedRealtime() - runStartMs) / 1000.0
            val cps = if (sec > 0.3) n / sec else 0.0
            countText?.text = "已点击 $n 次"
            rateText?.text = String.format(Locale.US, "%.1f 次/秒 · 按下“停止”即刻终止", cps)
            ui.postDelayed(this, 250L)
        }
    }

    /** 只能在主线程调用。 */
    private fun setRunningUi(on: Boolean) {
        normalRow?.visibility = if (on) View.GONE else View.VISIBLE
        runRow?.visibility = if (on) View.VISIBLE else View.GONE
        headTitle?.text = if (on) "运行中" else "点击器"
        headTitle?.setTextColor(if (on) AppleGlassUi.ACCENT_GREEN else AppleGlassUi.TEXT_PRIMARY)
        setMarkersLocked(on)
        ui.removeCallbacks(statsTick)
        if (on) {
            countText?.text = "已点击 0 次"
            rateText?.text = "准备中…"
            ui.post(statsTick)
        }
    }

    // ───────────────────────── 单点设置弹窗 ─────────────────────────

    private fun editPoint(point: ClickPoint) {
        if (running.get() || point !in points) return
        val themed = lightDialogCtx()
        val box = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(4))
        }

        fun field(label: String, value: String): EditText {
            box.addView(TextView(themed).apply {
                text = label
                textSize = 12f
                setTextColor(AppleGlassUi.TEXT_SECONDARY)
                setPadding(0, dp(10), 0, dp(2))
            })
            val et = EditText(themed).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value)
                setSelectAllOnFocus(true)
            }
            box.addView(et)
            return et
        }

        val countInput = field("点击次数（0 = 无限）", point.count.toString())
        val intervalInput = field("点击间隔（毫秒，越小越快）", point.intervalMs.toString())
        val tapMsInput = field("单次按住时长（毫秒，默认 20）", point.tapDurationMs.toString())
        val offsetInput = field("随机偏移半径（像素，0 = 关闭）", point.offsetPx.toString())

        val applyOffsetAll = CheckBox(themed).apply {
            text = "将随机偏移应用到所有点击点"
            setPadding(0, dp(8), 0, dp(4))
        }
        box.addView(applyOffsetAll)

        val longPressBox = CheckBox(themed).apply {
            text = "启用长按模式"
            isChecked = point.longPress
            setPadding(0, dp(4), 0, dp(4))
        }
        box.addView(longPressBox)

        val holdInput = field("长按持续时长（毫秒）", point.holdMs.toString())

        showDialog(
            AlertDialog.Builder(themed)
                .setTitle("点击点 #${points.indexOf(point) + 1} 设置")
                .setView(ScrollView(themed).apply { addView(box) })
                .setPositiveButton("保存") { _, _ ->
                    point.count = max(0, countInput.text.toString().toIntOrNull() ?: 1)
                    point.intervalMs = max(1L, intervalInput.text.toString().toLongOrNull() ?: 50L)
                    point.tapDurationMs = (tapMsInput.text.toString().toLongOrNull() ?: 20L).coerceIn(5L, 5000L)
                    point.longPress = longPressBox.isChecked
                    point.holdMs = max(50L, holdInput.text.toString().toLongOrNull() ?: 1000L)
                    val off = (offsetInput.text.toString().toIntOrNull() ?: 0).coerceIn(0, 500)
                    if (applyOffsetAll.isChecked) {
                        points.forEach { it.offsetPx = off }
                        toast("已将随机偏移 ${off}px 应用到全部 ${points.size} 个点")
                    } else {
                        point.offsetPx = off
                    }
                }
                .setNeutralButton("删除点") { _, _ -> removePoint(point) }
                .setNegativeButton("取消", null)
        )
    }

    // ───────────────────────── 点击执行引擎 ─────────────────────────

    private fun snapshotTargets(): List<ClickTarget> = markers.map { m ->
        val loc = IntArray(2)
        m.view.getLocationOnScreen(loc)
        ClickTarget(
            p = m.point,
            cx = loc[0] + m.view.width / 2,
            cy = loc[1] + m.view.height / 2
        )
    }

    private fun startClicks() {
        if (running.get()) return
        if (points.isEmpty()) {
            toast("请先点“增加”添加点击点")
            return
        }
        val mode = PresetManager.getMode(this)
        if (mode == ExecMode.ACCESSIBILITY && !ClickAccessibilityService.isEnabled()) {
            toast("无障碍服务未开启，请先在主界面开启无障碍服务", true)
            return
        }

        // 拷贝一份参数快照，运行中修改不影响本次任务
        val targets = snapshotTargets().map { ClickTarget(it.p.copy(), it.cx, it.cy) }
        val (sw, sh) = screenSize()
        val src = ActionSource(targets, PresetManager.isLoopEnabled(this), sw, sh)

        clickCount.set(0L)
        runStartMs = SystemClock.elapsedRealtime()
        running.set(true)
        setRunningUi(true)
        vibrateLight()

        val dispatcher = if (mode == ExecMode.ROOT) Dispatchers.IO else Dispatchers.Default
        val job = scope.launch(dispatcher) {
            try {
                when (mode) {
                    ExecMode.ACCESSIBILITY -> runA11yEngine(src)
                    ExecMode.ROOT -> runRootEngine(src)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (running.get()) {
                    toast("已停止：${e.message ?: "执行异常"}", true)
                }
            } finally {
                val me = coroutineContext[Job]
                ui.post {
                    // 只有“自然结束/出错”才走这里；用户点停止时 runner 已被置空，不会重复处理
                    if (runner === me) {
                        runner = null
                        running.set(false)
                        closeRoot()
                        setRunningUi(false)
                    }
                }
            }
        }
        runner = job
    }

    /**
     * 无障碍引擎：把连续多次点击打包成一个多笔画手势下发（每批最多 10 次、约 300ms），
     * 批与批之间无缝衔接；停止时最多只需等当前这一小批（<0.3 秒）结束。
     */
    private suspend fun CoroutineScope.runA11yEngine(src: ActionSource) {
        val maxStrokes = minOf(GestureDescription.getMaxStrokeCount(), 10)
        val batch = ArrayList<TapSpec>(maxStrokes)
        var fails = 0
        while (isActive && running.get()) {
            val svc = ClickAccessibilityService.instance
                ?: throw IllegalStateException("无障碍服务已断开")

            batch.clear()
            var offset = 0L
            while (batch.size < maxStrokes) {
                val a = src.next() ?: break
                batch.add(TapSpec(a.x, a.y, offset, a.dur))
                offset += a.stride
                if (a.isLong || offset >= BATCH_SPAN_MS) break
            }
            if (batch.isEmpty()) break

            if (!running.get()) break
            val t0 = SystemClock.elapsedRealtime()
            val ok = svc.dispatchBatch(batch)
            if (ok) {
                fails = 0
                clickCount.addAndGet(batch.size.toLong())
            } else {
                // 被系统取消（例如用户正在触摸屏幕）不算错误，稍等后继续
                if (!running.get()) break
                fails++
                if (fails >= 40) throw IllegalStateException("手势多次被系统取消，请检查无障碍服务")
                delay(25L)
                continue
            }
            if (src.isFinished) break
            val remain = offset - (SystemClock.elapsedRealtime() - t0)
            if (remain > 0L) delay(remain)
        }
    }

    private suspend fun CoroutineScope.runRootEngine(src: ActionSource) {
        if (!openRoot()) {
            throw IOException("Root 授权失败，请在 Magisk/KernelSU 中允许本应用")
        }
        while (isActive && running.get()) {
            val a = src.next() ?: break
            val t0 = SystemClock.elapsedRealtime()
            if (!rootTap(a)) {
                if (!running.get()) break
                throw IOException("Root 通道已断开或执行失败")
            }
            clickCount.incrementAndGet()
            if (src.isFinished) break
            val remain = a.stride - (SystemClock.elapsedRealtime() - t0)
            if (remain > 0L) delay(remain)
        }
    }

    /**
     * 停止：先立刻把运行标志置 false（引擎每批都会检查），再取消协程、断开 Root 通道、恢复界面。
     * 可以由悬浮面板、通知栏按钮、或再次启动服务（ACTION_STOP）触发。
     */
    private fun stopClicks() {
        val was = running.getAndSet(false)
        runner?.cancel()
        runner = null
        closeRoot()
        setRunningUi(false)
        if (was) vibrateLight()
    }

    // ───────────────────────── Root 通道 ─────────────────────────

    private fun startShell(): Boolean {
        return try {
            val proc = ProcessBuilder("su").redirectErrorStream(true).start()
            rootProcess = proc
            rootOut = DataOutputStream(proc.outputStream)
            rootIn = BufferedReader(InputStreamReader(proc.inputStream))
            true
        } catch (_: Exception) {
            closeRoot()
            false
        }
    }

    /** 优先启动“极速注入器”；失败则退回到逐条 `input` 命令的兼容模式。 */
    private fun openRoot(): Boolean {
        closeRoot()
        rootFast = false
        if (startShell()) {
            val proc = rootProcess
            val killer = Runnable {
                try {
                    proc?.destroy()
                } catch (_: Exception) {
                }
            }
            ui.postDelayed(killer, 20000L)
            var ready = false
            try {
                val apk = applicationInfo.sourceDir
                rootOut?.writeBytes("CLASSPATH='$apk' exec app_process /system/bin com.rootclicker.app.RootInjector\n")
                rootOut?.flush()
                val input = rootIn
                while (input != null) {
                    val line = input.readLine() ?: break
                    val t = line.trim()
                    if (t == "READY") {
                        ready = true
                        break
                    }
                    if (t.startsWith("ERR")) break
                }
            } catch (_: Exception) {
            }
            ui.removeCallbacks(killer)
            if (ready) {
                rootFast = true
                return true
            }
        }
        return openLegacy()
    }

    private fun openLegacy(): Boolean {
        closeRoot()
        rootFast = false
        if (!startShell()) return false
        val r = runRoot("id")
        if (r != null && r.contains("uid=0")) return true
        closeRoot()
        return false
    }

    private fun rootTap(a: Action): Boolean {
        if (rootFast) {
            val r = runFast("T ${a.x} ${a.y} ${a.dur}")
            if (r == "OK") return true
            if (!running.get()) return false
            toast("极速注入不可用，已切换为兼容模式")
            if (!openLegacy()) return false
        }
        val cmd = if (!a.isLong && a.dur <= 50L) {
            "input tap ${a.x} ${a.y}"
        } else {
            "input swipe ${a.x} ${a.y} ${a.x} ${a.y} ${a.dur}"
        }
        return runRoot(cmd) != null
    }

    private fun runFast(cmd: String): String? {
        val out = rootOut ?: return null
        val input = rootIn ?: return null
        var res: String? = null
        try {
            out.writeBytes("$cmd\n")
            out.flush()
            while (res == null) {
                val line = input.readLine() ?: break
                val t = line.trim()
                if (t == "OK" || t.startsWith("ERR")) res = t
            }
        } catch (_: IOException) {
            return null
        }
        return res
    }

    private fun runRoot(cmd: String): String? {
        val out = rootOut ?: return null
        val input = rootIn ?: return null
        return try {
            out.writeBytes("$cmd\necho $DONE\n")
            out.flush()
            val sb = StringBuilder()
            while (true) {
                val line = input.readLine() ?: return null
                if (line.contains(DONE)) break
                sb.append(line).append('\n')
            }
            sb.toString()
        } catch (_: IOException) {
            null
        }
    }

    /** 在后台线程关闭，避免主线程（停止按钮）被阻塞。 */
    private fun closeRoot() {
        val p = rootProcess
        val o = rootOut
        rootProcess = null
        rootOut = null
        rootIn = null
        if (p != null || o != null) {
            Thread {
                try {
                    p?.destroy()
                } catch (_: Exception) {
                }
                try {
                    o?.close()
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "点击器", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        running.set(false)
        runner?.cancel()
        runner = null
        closeRoot()
        ui.removeCallbacksAndMessages(null)
        clearAllMarkers()
        panel?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
