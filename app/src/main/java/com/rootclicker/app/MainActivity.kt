package com.rootclicker.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var modeA11yBtn: TextView
    private lateinit var modeRootBtn: TextView
    private lateinit var modeStatusText: TextView
    private lateinit var overlayStatusText: TextView
    private lateinit var presetSummaryText: TextView
    private lateinit var defaultIntervalInput: EditText
    private lateinit var defaultTapInput: EditText
    private lateinit var defaultOffsetInput: EditText
    private lateinit var loopSwitch: Switch
    private lateinit var hapticSwitch: Switch
    private lateinit var presetListContainer: LinearLayout

    private fun dp(v: Int): Int = AppleGlassUi.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(AuroraDrawable())
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        lightSystemBars()

        buildUi()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @Suppress("DEPRECATION")
    private fun lightSystemBars() {
        var f = window.decorView.systemUiVisibility
        f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = f
    }

    override fun onResume() {
        super.onResume()
        refreshAllStates()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun newCard(): LinearLayout = AppleGlassUi.card(this, 22f).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(36))
        }
        scroll.addView(root)
        setContentView(scroll)

        // ── 顶部标题 ──
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        titleRow.addView(TextView(this).apply {
            text = "点击器"
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(AppleGlassUi.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = "v1.3"
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(AppleGlassUi.ACCENT_BLUE)
            background = AppleGlassUi.glass(this@MainActivity, AppleGlassUi.TINT_BLUE, 0, 12f, 0)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        })
        root.addView(titleRow)
        root.addView(TextView(this).apply {
            text = "无障碍 / Root 双引擎 · 批量手势极速连点 · 方案一键切换"
            textSize = 13f
            setTextColor(AppleGlassUi.TEXT_SECONDARY)
            setPadding(dp(4), dp(6), dp(4), dp(18))
        })

        // ── 1. 执行引擎 ──
        val modeCard = newCard()
        modeCard.addView(sectionTitle("执行引擎", "随时切换，立即生效"))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = AppleGlassUi.glass(this@MainActivity, 0x1A767680, 0, 12f, 0)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply { topMargin = dp(12) }
        }
        modeA11yBtn = makeSegmentTab("无障碍") {
            PresetManager.setMode(this, ExecMode.ACCESSIBILITY)
            refreshAllStates()
            if (!ClickAccessibilityService.isEnabled()) promptEnableAccessibility()
        }
        modeRootBtn = makeSegmentTab("Root") {
            PresetManager.setMode(this, ExecMode.ROOT)
            refreshAllStates()
            testRootAvailability()
        }
        seg.addView(modeA11yBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        seg.addView(modeRootBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        modeCard.addView(seg)
        modeStatusText = TextView(this).apply {
            textSize = 12f
            setTextColor(AppleGlassUi.TEXT_SECONDARY)
            setPadding(dp(2), dp(10), dp(2), 0)
        }
        modeCard.addView(modeStatusText)
        root.addView(modeCard)

        // ── 2. 权限与悬浮面板 ──
        val permCard = newCard()
        permCard.addView(sectionTitle("权限与面板", "授权后即可打开悬浮控制面板"))
        overlayStatusText = TextView(this).apply {
            textSize = 12f
            setTextColor(AppleGlassUi.TEXT_SECONDARY)
            setPadding(dp(2), dp(8), dp(2), dp(12))
        }
        permCard.addView(overlayStatusText)

        val permRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val permBtn = AppleGlassUi.pillButton(this, "悬浮窗授权", AppleGlassUi.TINT_GRAY, AppleGlassUi.ACCENT_BLUE, 14f) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val a11yBtn = AppleGlassUi.pillButton(this, "无障碍设置", AppleGlassUi.TINT_GRAY, AppleGlassUi.ACCENT_BLUE, 14f) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permRow.addView(permBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        permRow.addView(a11yBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        permCard.addView(permRow)

        val launchBtn = AppleGlassUi.pillButton(this, "打开悬浮面板", AppleGlassUi.ACCENT_BLUE, Color.WHITE, 16f, 16f) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                if (PresetManager.getMode(this) == ExecMode.ACCESSIBILITY && !ClickAccessibilityService.isEnabled()) {
                    Toast.makeText(this, "当前为无障碍模式，请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                }
                startForegroundService(Intent(this, OverlayService::class.java))
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply { topMargin = dp(10) }
        }
        permCard.addView(launchBtn)
        root.addView(permCard)

        // ── 3. 默认参数 ──
        val tuneCard = newCard()
        tuneCard.addView(sectionTitle("默认参数", "新增点击点时自动套用，也可长按点击点单独调整"))
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(6))
        }
        val (intervalBox, intervalEt) = labeledInput("间隔 (ms)", PresetManager.getDefaultIntervalMs(this).toString())
        val (tapBox, tapEt) = labeledInput("按住 (ms)", PresetManager.getDefaultTapMs(this).toString())
        val (offsetBox, offsetEt) = labeledInput("偏移 (px)", PresetManager.getDefaultOffset(this).toString())
        defaultIntervalInput = intervalEt
        defaultTapInput = tapEt
        defaultOffsetInput = offsetEt
        inputRow.addView(intervalBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        inputRow.addView(tapBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        inputRow.addView(offsetBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tuneCard.addView(inputRow)
        tuneCard.addView(TextView(this).apply {
            text = "间隔越小点得越快（最低 1ms，实际速度受系统限制）；按住时长越短越快，一般 10~30ms 即可。"
            textSize = 11f
            setTextColor(AppleGlassUi.TEXT_TERTIARY)
            setPadding(dp(2), 0, dp(2), dp(8))
        })

        loopSwitch = Switch(this).apply {
            text = "多个点击点循环执行"
            isChecked = PresetManager.isLoopEnabled(this@MainActivity)
            setTextColor(AppleGlassUi.TEXT_PRIMARY)
            textSize = 14f
            setPadding(dp(2), dp(10), dp(2), dp(10))
            setOnCheckedChangeListener { _, checked -> PresetManager.setLoopEnabled(this@MainActivity, checked) }
        }
        tuneCard.addView(AppleGlassUi.divider(this))
        tuneCard.addView(loopSwitch)
        tuneCard.addView(AppleGlassUi.divider(this))
        hapticSwitch = Switch(this).apply {
            text = "操作触感反馈"
            isChecked = PresetManager.isHapticEnabled(this@MainActivity)
            setTextColor(AppleGlassUi.TEXT_PRIMARY)
            textSize = 14f
            setPadding(dp(2), dp(10), dp(2), dp(10))
            setOnCheckedChangeListener { _, checked -> PresetManager.setHapticEnabled(this@MainActivity, checked) }
        }
        tuneCard.addView(hapticSwitch)

        val saveTuneBtn = AppleGlassUi.pillButton(this, "保存默认参数", AppleGlassUi.TINT_GREEN, 0xFF1E9E45.toInt(), 14f) {
            val interval = (defaultIntervalInput.text.toString().toLongOrNull() ?: PresetManager.DEFAULT_INTERVAL_MS)
                .coerceIn(1L, 600000L)
            val tapMs = (defaultTapInput.text.toString().toLongOrNull() ?: PresetManager.DEFAULT_TAP_MS)
                .coerceIn(5L, 5000L)
            val offPx = (defaultOffsetInput.text.toString().toIntOrNull() ?: 6).coerceIn(0, 500)
            PresetManager.setDefaultIntervalMs(this, interval)
            PresetManager.setDefaultTapMs(this, tapMs)
            PresetManager.setDefaultOffset(this, offPx)
            defaultIntervalInput.setText(interval.toString())
            defaultTapInput.setText(tapMs.toString())
            defaultOffsetInput.setText(offPx.toString())
            Toast.makeText(this, "已保存：间隔 ${interval}ms · 按住 ${tapMs}ms · 偏移 ${offPx}px", Toast.LENGTH_SHORT).show()
            refreshAllStates()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { topMargin = dp(8) }
        }
        tuneCard.addView(saveTuneBtn)
        root.addView(tuneCard)

        // ── 4. 点击方案 ──
        val presetCard = newCard()
        val presetHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val presetTitleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        presetTitleBox.addView(TextView(this).apply {
            text = "点击方案"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(AppleGlassUi.TEXT_PRIMARY)
        })
        presetSummaryText = TextView(this).apply {
            textSize = 12f
            setTextColor(AppleGlassUi.TEXT_SECONDARY)
            setPadding(0, dp(2), 0, 0)
        }
        presetTitleBox.addView(presetSummaryText)
        val newPresetBtn = AppleGlassUi.circleButton(
            this, "新建", 40, 12f, AppleGlassUi.TINT_BLUE, AppleGlassUi.ACCENT_BLUE
        ) { showCreatePresetDialog() }
        presetHeader.addView(presetTitleBox)
        presetHeader.addView(newPresetBtn)
        presetCard.addView(presetHeader)

        presetListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        presetCard.addView(presetListContainer)
        root.addView(presetCard)
    }

    private fun sectionTitle(title: String, desc: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(AppleGlassUi.TEXT_PRIMARY)
        })
        addView(TextView(this@MainActivity).apply {
            text = desc
            textSize = 12f
            setTextColor(AppleGlassUi.TEXT_SECONDARY)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun labeledInput(label: String, value: String): Pair<LinearLayout, EditText> {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value)
            setSingleLine(true)
            setTextColor(AppleGlassUi.TEXT_PRIMARY)
            textSize = 15f
            background = AppleGlassUi.fieldBg(this@MainActivity)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(AppleGlassUi.TEXT_SECONDARY)
        })
        box.addView(et)
        return Pair(box, et)
    }

    private fun makeSegmentTab(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun refreshAllStates() {
        val mode = PresetManager.getMode(this)
        val isA11y = mode == ExecMode.ACCESSIBILITY
        val selectedBg = { AppleGlassUi.glass(this, Color.WHITE, 0x14000000, 10f, 1) }

        modeA11yBtn.background = if (isA11y) selectedBg() else null
        modeA11yBtn.setTextColor(if (isA11y) AppleGlassUi.TEXT_PRIMARY else AppleGlassUi.TEXT_SECONDARY)
        modeRootBtn.background = if (!isA11y) selectedBg() else null
        modeRootBtn.setTextColor(if (!isA11y) AppleGlassUi.TEXT_PRIMARY else AppleGlassUi.TEXT_SECONDARY)

        val a11yReady = ClickAccessibilityService.isEnabled()
        modeStatusText.text = if (isA11y) {
            if (a11yReady) "无障碍服务已连接 · 批量手势引擎，连点更快更稳"
            else "无障碍服务未开启，请点下方“无障碍设置”，找到“点击器”并开启"
        } else {
            "Root 模式 · 优先使用极速注入，不可用时自动降级为兼容模式"
        }
        modeStatusText.setTextColor(
            if (isA11y && !a11yReady) AppleGlassUi.ACCENT_AMBER else AppleGlassUi.TEXT_SECONDARY
        )

        val canOverlay = Settings.canDrawOverlays(this)
        overlayStatusText.text = if (canOverlay)
            "悬浮窗权限已授权 · 默认间隔 ${PresetManager.getDefaultIntervalMs(this)}ms · 按住 ${PresetManager.getDefaultTapMs(this)}ms"
        else
            "悬浮窗权限未授权（需要它才能显示控制面板和点击点）"
        overlayStatusText.setTextColor(if (canOverlay) 0xFF1E9E45.toInt() else AppleGlassUi.ACCENT_AMBER)

        renderPresetList()
    }

    private fun renderPresetList() {
        presetListContainer.removeAllViews()
        val presets = PresetManager.loadPresets(this)
        val activeName = PresetManager.getLastPresetName(this)
        presetSummaryText.text = if (presets.isEmpty())
            "还没有方案"
        else
            "共 ${presets.size} 套 · 当前：${activeName ?: "无"}"

        if (presets.isEmpty()) {
            presetListContainer.addView(TextView(this).apply {
                text = "在悬浮面板里摆好点击点，点“保存”即可存成方案，下次一键载入。"
                textSize = 12f
                setTextColor(AppleGlassUi.TEXT_SECONDARY)
                setPadding(dp(2), dp(4), dp(2), dp(4))
            })
            return
        }

        for (preset in presets) {
            val isSelected = preset.name == activeName
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = AppleGlassUi.glass(
                    this@MainActivity,
                    if (isSelected) 0x1F007AFF else 0x8CFFFFFF.toInt(),
                    if (isSelected) 0x66007AFF else AppleGlassUi.HAIRLINE,
                    16f,
                    1
                )
                setPadding(dp(14), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = preset.name
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (isSelected) AppleGlassUi.ACCENT_BLUE else AppleGlassUi.TEXT_PRIMARY)
            })
            info.addView(TextView(this).apply {
                text = "${preset.points.size} 个点击点" + if (isSelected) " · 当前使用" else ""
                textSize = 12f
                setTextColor(AppleGlassUi.TEXT_SECONDARY)
                setPadding(0, dp(2), 0, 0)
            })

            val loadBtn = AppleGlassUi.circleButton(
                this, "载入", 40, 12f, AppleGlassUi.TINT_GREEN, 0xFF1E9E45.toInt()
            ) {
                PresetManager.setLastPresetName(this, preset.name)
                refreshAllStates()
                if (Settings.canDrawOverlays(this)) {
                    val intent = Intent(this, OverlayService::class.java).apply {
                        putExtra(OverlayService.EXTRA_LOAD_PRESET, preset.name)
                    }
                    startForegroundService(intent)
                    Toast.makeText(this, "已载入方案：${preset.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "已选中“${preset.name}”，授权悬浮窗后自动加载", Toast.LENGTH_SHORT).show()
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
            }

            val delBtn = AppleGlassUi.circleButton(
                this, "删除", 40, 12f, AppleGlassUi.TINT_RED, AppleGlassUi.ACCENT_RED
            ) {
                PresetManager.deletePreset(this, preset.name)
                refreshAllStates()
                Toast.makeText(this, "已删除：${preset.name}", Toast.LENGTH_SHORT).show()
            }

            row.addView(info)
            row.addView(loadBtn)
            row.addView(delBtn)
            presetListContainer.addView(row)
        }
    }

    private fun lightBuilder(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)

    private fun showDialog(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        AppleGlassUi.styleDialog(this, dialog)
        dialog.show()
        AppleGlassUi.blurDialog(dialog)
    }

    private fun showCreatePresetDialog() {
        val input = EditText(this).apply {
            hint = "给方案起个名字，例如：签到"
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        showDialog(
            lightBuilder()
                .setTitle("新建点击方案")
                .setView(box)
                .setPositiveButton("创建") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(this, "方案名称不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val dm = resources.displayMetrics
                    val spec = PointSpec(
                        x = dm.widthPixels / 2,
                        y = dm.heightPixels / 2,
                        count = 1,
                        intervalMs = PresetManager.getDefaultIntervalMs(this),
                        tapDurationMs = PresetManager.getDefaultTapMs(this),
                        longPress = false,
                        holdMs = 1000L,
                        offsetPx = PresetManager.getDefaultOffset(this)
                    )
                    PresetManager.savePreset(this, name, listOf(spec))
                    refreshAllStates()
                    Toast.makeText(this, "已创建方案：$name", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun promptEnableAccessibility() {
        showDialog(
            lightBuilder()
                .setTitle("开启无障碍服务")
                .setMessage("无障碍模式需要开启“点击器”无障碍服务，才能执行点击手势。是否现在前往设置？")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("稍后", null)
        )
    }

    private fun testRootAvailability() {
        uiScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val p = ProcessBuilder("su", "-c", "id").start()
                val out = p.inputStream.bufferedReader().readText()
                val code = p.waitFor()
                code == 0 && out.contains("uid=0")
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "Root 权限可用 (uid=0)" else "未检测到可用 Root 权限，可切回无障碍模式",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
