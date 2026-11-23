package com.rootclicker.app

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Root 极速注入器：由 `su` 通过 app_process 直接启动（不是普通 Activity/Service）。
 * 它常驻后台，通过 stdin 接收 "T x y 时长ms" 指令，直接调用系统输入服务注入触摸事件。
 * 比每次执行 `input tap`（每次都要启动一个 Java 进程，约 100ms+）快一个数量级。
 * 协议：启动成功输出 READY；每条指令执行完输出 OK；出错输出 ERR ...
 */
object RootInjector {
    private var target: Any? = null
    private var injectMethod: Method? = null

    private fun reply(s: String) {
        System.out.println(s)
        System.out.flush()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            setup()
        } catch (e: Throwable) {
            reply("ERR setup ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        reply("READY")
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            val p = line.trim().split(" ")
            try {
                when (p[0]) {
                    "T" -> {
                        tap(p[1].toFloat(), p[2].toFloat(), p[3].toLong())
                        reply("OK")
                    }
                    "Q" -> return
                    "" -> {}
                    else -> reply("ERR unknown")
                }
            } catch (e: Throwable) {
                reply("ERR ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun setup() {
        val errors = StringBuilder()
        val strategies = listOf<() -> Pair<Any, Method>>(
            { viaGlobal() },
            { viaManager() },
            { viaService() }
        )
        for (s in strategies) {
            try {
                val (o, m) = s()
                target = o
                injectMethod = m
                return
            } catch (e: Throwable) {
                errors.append(e.javaClass.simpleName).append(';')
            }
        }
        throw RuntimeException(errors.toString())
    }

    // Android 14+
    private fun viaGlobal(): Pair<Any, Method> {
        val cls = Class.forName("android.hardware.input.InputManagerGlobal")
        val getInstance = cls.getDeclaredMethod("getInstance")
        getInstance.isAccessible = true
        val inst = getInstance.invoke(null)!!
        val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        m.isAccessible = true
        return Pair(inst, m)
    }

    // Android 8 ~ 13
    private fun viaManager(): Pair<Any, Method> {
        val cls = Class.forName("android.hardware.input.InputManager")
        val getInstance = cls.getDeclaredMethod("getInstance")
        getInstance.isAccessible = true
        val inst = getInstance.invoke(null)!!
        val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        m.isAccessible = true
        return Pair(inst, m)
    }

    // 兜底：直接拿 input 系统服务的 binder
    private fun viaService(): Pair<Any, Method> {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        val im = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        val m = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        m.isAccessible = true
        return Pair(im, m)
    }

    private fun inject(ev: MotionEvent) {
        try {
            // 0 = INJECT_INPUT_EVENT_MODE_ASYNC，不等待应用处理，最快
            injectMethod!!.invoke(target, ev, 0)
        } finally {
            ev.recycle()
        }
    }

    private fun tap(x: Float, y: Float, holdMs: Long) {
        val down = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN)
        inject(d)
        if (holdMs > 0L) Thread.sleep(holdMs)
        val up = SystemClock.uptimeMillis()
        val u = MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, y, 0)
        u.setSource(InputDevice.SOURCE_TOUCHSCREEN)
        inject(u)
    }
}
