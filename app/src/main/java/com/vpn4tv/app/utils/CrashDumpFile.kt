package com.vpn4tv.app.utils

import java.io.File
import java.io.RandomAccessFile

/**
 * A Go crash dump read for the log and the upload: at most [HEAD] bytes from
 * its start (the fatal error, the runtime's preamble and the crashing
 * goroutine come first) and [TAIL] bytes from its end, the cut marked. One
 * device's go_crash.log.stderr had grown to about 64 MB; read whole into a
 * String it ran the app out of memory at every launch (5.4.2, crash group
 * 299f0bdf6e).
 */
object CrashDumpFile {
    const val HEAD = 256 * 1024
    const val TAIL = 768 * 1024

    fun read(file: File, head: Int = HEAD, tail: Int = TAIL): String =
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length <= head.toLong() + tail) {
                val bytes = ByteArray(length.toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            } else {
                val start = ByteArray(head)
                raf.readFully(start)
                val end = ByteArray(tail)
                raf.seek(length - tail)
                raf.readFully(end)
                buildString {
                    append(String(start, Charsets.UTF_8))
                    append("\n… ").append(length - head - tail).append(" bytes skipped …\n")
                    append(String(end, Charsets.UTF_8))
                }
            }
        }
}
