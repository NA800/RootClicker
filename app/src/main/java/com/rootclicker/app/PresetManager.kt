package com.rootclicker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ExecMode(val label: String, val shortTag: String) {
    ACCESSIBILITY("无障碍模式", "无障碍"),
    ROOT("Root 模式", "Root")
}

data class PointSpec(
    val x: Int,
    val y: Int,
    val count: Int = 1,
    val intervalMs: Long = 50L,
    val tapDurationMs: Long = 20L,
    val longPress: Boolean = false,
    val holdMs: Long = 1000L,
    val offsetPx: Int = 0
)

data class ClickPreset(
    val name: String,
    val points: List<PointSpec>
)

object PresetManager {
    private const val PREFS_NAME = "clickflow_prefs"
    private const val KEY_MODE = "exec_mode"
    private const val KEY_PRESETS = "presets_json"
    private const val KEY_LAST_PRESET = "last_preset_name"
    private const val KEY_DEFAULT_OFFSET = "default_offset_px"
    private const val KEY_DEFAULT_TAP_MS = "default_tap_ms_v2"
    private const val KEY_DEFAULT_INTERVAL = "default_interval_ms"
    private const val KEY_LOOP_ENABLED = "loop_enabled"
    private const val KEY_HAPTIC_ENABLED = "haptic_enabled"

    const val DEFAULT_TAP_MS = 20L
    const val DEFAULT_INTERVAL_MS = 50L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): ExecMode {
        val raw = prefs(ctx).getString(KEY_MODE, ExecMode.ACCESSIBILITY.name)
        return runCatching { ExecMode.valueOf(raw ?: ExecMode.ACCESSIBILITY.name) }
            .getOrDefault(ExecMode.ACCESSIBILITY)
    }

    fun setMode(ctx: Context, mode: ExecMode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getDefaultOffset(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DEFAULT_OFFSET, 6).coerceIn(0, 500)

    fun setDefaultOffset(ctx: Context, px: Int) {
        prefs(ctx).edit().putInt(KEY_DEFAULT_OFFSET, px.coerceIn(0, 500)).apply()
    }

    fun getDefaultTapMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_DEFAULT_TAP_MS, DEFAULT_TAP_MS).coerceIn(5L, 5000L)

    fun setDefaultTapMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(KEY_DEFAULT_TAP_MS, ms.coerceIn(5L, 5000L)).apply()
    }

    fun getDefaultIntervalMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_DEFAULT_INTERVAL, DEFAULT_INTERVAL_MS).coerceIn(1L, 600000L)

    fun setDefaultIntervalMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(KEY_DEFAULT_INTERVAL, ms.coerceIn(1L, 600000L)).apply()
    }

    fun isLoopEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LOOP_ENABLED, true)

    fun setLoopEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOOP_ENABLED, enabled).apply()
    }

    fun isHapticEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_HAPTIC_ENABLED, true)

    fun setHapticEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    fun getLastPresetName(ctx: Context): String? =
        prefs(ctx).getString(KEY_LAST_PRESET, null)

    fun setLastPresetName(ctx: Context, name: String?) {
        prefs(ctx).edit().putString(KEY_LAST_PRESET, name).apply()
    }

    fun loadPresets(ctx: Context): MutableList<ClickPreset> {
        val raw = prefs(ctx).getString(KEY_PRESETS, "[]") ?: "[]"
        val list = mutableListOf<ClickPreset>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue
                val ptsArr = obj.optJSONArray("points") ?: JSONArray()
                val pts = mutableListOf<PointSpec>()
                for (j in 0 until ptsArr.length()) {
                    val p = ptsArr.optJSONObject(j) ?: continue
                    pts.add(
                        PointSpec(
                            x = p.optInt("x", 300),
                            y = p.optInt("y", 600),
                            count = p.optInt("count", 1).coerceAtLeast(0),
                            intervalMs = p.optLong("intervalMs", DEFAULT_INTERVAL_MS).coerceAtLeast(1L),
                            tapDurationMs = p.optLong("tapDurationMs", DEFAULT_TAP_MS).coerceIn(5L, 5000L),
                            longPress = p.optBoolean("longPress", false),
                            holdMs = p.optLong("holdMs", 1000L).coerceAtLeast(1L),
                            offsetPx = p.optInt("offsetPx", 0).coerceIn(0, 500)
                        )
                    )
                }
                list.add(ClickPreset(name = name, points = pts))
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun savePreset(ctx: Context, name: String, points: List<PointSpec>): Boolean {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return false
        val list = loadPresets(ctx)
        val newPreset = ClickPreset(cleanName, points.toList())
        val idx = list.indexOfFirst { it.name == cleanName }
        if (idx >= 0) list[idx] = newPreset else list.add(0, newPreset)
        persistPresets(ctx, list)
        setLastPresetName(ctx, cleanName)
        return true
    }

    fun deletePreset(ctx: Context, name: String) {
        val list = loadPresets(ctx).filterNot { it.name == name }
        persistPresets(ctx, list)
        if (getLastPresetName(ctx) == name) {
            setLastPresetName(ctx, list.firstOrNull()?.name)
        }
    }

    private fun persistPresets(ctx: Context, list: List<ClickPreset>) {
        val arr = JSONArray()
        for (preset in list) {
            val obj = JSONObject()
            obj.put("name", preset.name)
            val ptsArr = JSONArray()
            for (pt in preset.points) {
                ptsArr.put(JSONObject().apply {
                    put("x", pt.x)
                    put("y", pt.y)
                    put("count", pt.count)
                    put("intervalMs", pt.intervalMs)
                    put("tapDurationMs", pt.tapDurationMs)
                    put("longPress", pt.longPress)
                    put("holdMs", pt.holdMs)
                    put("offsetPx", pt.offsetPx)
                })
            }
            obj.put("points", ptsArr)
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY_PRESETS, arr.toString()).apply()
    }
}
