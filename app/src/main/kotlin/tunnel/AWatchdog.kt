package tunnel

import android.content.Context
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.with
import core.IWatchdog
import core.Tunnel
import gs.environment.Journal
import gs.environment.Worker
import gs.environment.inject
import gs.property.Device
import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.task
import java.net.InetSocketAddress
import java.net.Socket

/**
 * AWatchdog is meant to test if device has Internet connectivity at this moment.
 *
 * It's used for getting connectivity state since Android's connectivity event cannot always be fully
 * trusted. It's also used to test if Blokada is working properly once activated (and periodically).
 */
class AWatchdog(
        private val ctx: Context
) : IWatchdog {

    private val s by lazy { ctx.inject().instance<Tunnel>() }
    private val d by lazy { ctx.inject().instance<Device>() }
    private val j by lazy { ctx.inject().instance<Journal>() }
    private val kctx by lazy { ctx.inject().with("watchdog").instance<Worker>() }

    override fun test(): Boolean {
        if (!s.watchdogOn()) return true
        val socket = Socket()
        socket.soTimeout = 3000
        return try { socket.connect(InetSocketAddress("dns.watch", 80), 3000); true }
        catch (e: Exception) { false } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private val MAX = 120
    private var started = false
    private var wait = 1
    private var nextTask: Promise<*, *>? = null

    @Synchronized override fun start() {
        if (started) return
        if (!s.watchdogOn()) { return }
        started = true
        wait = 1
        if (nextTask != null) Kovenant.cancel(nextTask!!, Exception("cancelled"))
        nextTask = tick()
    }

    @Synchronized override fun stop() {
        started = false
        if (nextTask != null) Kovenant.cancel(nextTask!!, Exception("cancelled"))
        nextTask = null
    }

    private fun tick(): Promise<*, *> {
        return task(kctx) {
            if (started) {
                // Delay the first check to not cause false positives
                if (wait == 1) Thread.sleep(1000L)
                val connected = test()
                val next = if (connected) wait * 2 else wait
                wait *= 2
                if (d.connected() != connected) {
                    // Connection state change will cause reactivating (and restarting watchdog)
                    j.log("watchdog change: connected: $connected")
                    d.connected %= connected // todo: this wont work
                    stop()
                } else {
                    Thread.sleep(Math.min(next, MAX) * 1000L)
                    nextTask = tick()
                }
            }
        }
    }
}
