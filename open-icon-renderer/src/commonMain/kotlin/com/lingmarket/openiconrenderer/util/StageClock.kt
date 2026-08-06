package com.lingmarket.openiconrenderer.util

import kotlin.time.TimeSource

/** Lightweight stage timer for extract profiling (Monotonic wall elapsed). */
internal class StageClock {
    private val markSource = TimeSource.Monotonic
    private val totalsNs = LinkedHashMap<String, Long>()
    private var activeName: String? = null
    private var activeMark: TimeSource.Monotonic.ValueTimeMark? = null

    fun start(name: String) {
        stop()
        activeName = name
        activeMark = markSource.markNow()
    }

    fun stop() {
        val name = activeName ?: return
        val mark = activeMark ?: return
        totalsNs[name] = (totalsNs[name] ?: 0L) + mark.elapsedNow().inWholeNanoseconds
        activeName = null
        activeMark = null
    }

    inline fun <T> measure(name: String, block: () -> T): T {
        start(name)
        return try {
            block()
        } finally {
            stop()
        }
    }

    fun report(prefix: String = "[OpenIconRenderer stages]") {
        stop()
        if (totalsNs.isEmpty()) return
        val parts = totalsNs.entries.joinToString("  ") { (k, ns) ->
            val ms = ns / 1_000_000.0
            val whole = ms.toLong()
            val frac = ((ms - whole) * 100.0).toLong().coerceIn(0, 99)
            val fracStr = if (frac < 10) "0$frac" else "$frac"
            "$k=${whole}.$fracStr ms"
        }
        println("$prefix $parts")
    }
}
