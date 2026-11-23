package com.rootclicker.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 一次点击：相对于本批手势起点的开始时间 + 按住时长。 */
class TapSpec(val x: Int, val y: Int, val startMs: Long, val durMs: Long)

class ClickAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: ClickAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    // 手势回调放到独立线程，不占用主线程，避免连点时界面（含停止按钮）卡顿
    private val cbThread = HandlerThread("clicker-gesture").also { it.start() }
    private val cbHandler = Handler(cbThread.looper)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        cbThread.quitSafely()
        super.onDestroy()
    }

    /**
     * 把多次点击合并成“一个多笔画手势”一次性下发（每笔 = 一次点击）。
     * 这样省掉了“每点一次就等一次系统回调”的往返开销，速度大幅提升，
     * 并且每次点击的时间点由系统精确调度。
     */
    suspend fun dispatchBatch(taps: List<TapSpec>): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val builder = GestureDescription.Builder()
                for (t in taps) {
                    val path = Path().apply { moveTo(t.x.toFloat(), t.y.toFloat()) }
                    builder.addStroke(GestureDescription.StrokeDescription(path, t.startMs, t.durMs))
                }
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val sent = dispatchGesture(builder.build(), callback, cbHandler)
                if (!sent && cont.isActive) cont.resume(false)
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
}
