package com.vpn4tv.app.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own lines for support, kept apart from the core's log: the
 * device's networks before a start and at every change after it, which of
 * them the core was told, whether the device gives the VPN's own connections
 * a route (NetworkWatch), how a start ended, why a key did not load.
 *
 * The core keeps its newest 3000 lines of every level, and a release build
 * reads only the warnings and errors out of them (CommandClient): a line the
 * app writes there at a start is gone after minutes of traffic. On a TV the
 * Logs screen cannot be scrolled with the remote either, so a photo of it
 * shows the newest lines only. Support case of 2026-11-21 (a Xiaomi TV, every
 * connection of the core "network is unreachable" with the VPN up): four days
 * of photos never showed how the session began.
 *
 * In memory, for the life of the process: the start of the running session
 * ([HEAD] lines) and its newest lines ([TAIL]), and the newest [PREVIOUS]
 * lines of the sessions before it. "Send to support" (SupportReport) and the
 * phone's Share send them; they hold no keys, links or sites, as the rest of
 * the logs (LogPrivacy).
 */
object SupportNotes {
    internal const val HEAD = 60
    internal const val TAIL = 120
    internal const val PREVIOUS = 60

    private val previous = ArrayDeque<String>()
    private val head = ArrayList<String>()
    private val tail = ArrayDeque<String>()
    private var dropped = 0

    /** A start of the VPN: what was noted before it becomes the past. */
    @Synchronized
    fun newSession(at: Long = System.currentTimeMillis()) {
        val old = shownSession()
        head.clear()
        tail.clear()
        dropped = 0
        old.forEach { previous.addLast(it) }
        while (previous.size > PREVIOUS) previous.removeFirst()
        head.add("${stamp(at)} — start —")
    }

    @Synchronized
    fun add(line: String, at: Long = System.currentTimeMillis()) {
        val text = "${stamp(at)} ${line.replace('\n', ' ').take(MAX_LINE)}"
        if (head.size < HEAD) {
            head.add(text)
            return
        }
        tail.addLast(text)
        if (tail.size > TAIL) {
            tail.removeFirst()
            dropped++
        }
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<String> = previous.toList() + shownSession()

    @Synchronized
    internal fun clear() {
        previous.clear()
        head.clear()
        tail.clear()
        dropped = 0
    }

    private fun shownSession(): List<String> =
        if (dropped == 0) head + tail else head + "… $dropped lines dropped …" + tail

    // A line that names every network of the device and the routes by each runs long.
    private const val MAX_LINE = 1500

    // The device's own clock and zone, as the Logs screen's lines; the report's header says the zone.
    private fun stamp(at: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(at))
}
