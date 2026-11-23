package com.vpn4tv.app.utils

/**
 * What a report keeps of the tombstone Android stores for a crash in native code
 * (ApplicationExitInfo.getTraceInputStream, Android 12+): the signal, the abort
 * message and the stack of the thread that crashed, each frame with the library,
 * the function and the address inside the library, which is what our own
 * libraries' symbol files turn into a source line.
 *
 * A crash in a C or C++ library (TrustTunnel, the naive transport inside the
 * core) leaves no Go dump and no Java stack: until 5.6.7 the app sent nothing,
 * and a week of such crashes (Google Play, 16-22 November 2026, TrustTunnel's
 * QUIC ping) was seen in Play's numbers only.
 *
 * The tombstone is a protobuf (AOSP system/core/debuggerd/proto/tombstone.proto).
 * Only the fields named here are read. Left out on purpose: the command line,
 * the registers, the memory near them, the memory map, the open files and the
 * process's log lines, which can name servers and sites.
 */
internal object NativeTombstone {

    /** Frames kept of the crashed thread. */
    const val MAX_FRAMES = 32
    private const val MAX_TEXT = 200

    class Frame(val relPc: Long, val function: String, val offset: Long, val file: String, val buildId: String)

    class Crash(
        val signal: String,
        val code: String,
        val hasFaultAddress: Boolean,
        val abortMessage: String,
        val thread: String,
        val frames: List<Frame>,
    )

    /** Null when [bytes] is not a tombstone that names the crashed thread. */
    fun parse(bytes: ByteArray): Crash? = try {
        read(bytes)
    } catch (_: RuntimeException) {
        // A truncated or foreign file: indexes past the end, a length that is not one.
        null
    }

    /**
     * The lines for the report:
     * ```
     * signal=SIGSEGV code=SEGV_ACCERR fault-address=yes
     * abort: <message>
     * thread "Thread-30"
     *   #00 pc 008436c0 libtrusttunnel_android.so (bufferevent_openssl_get_ssl+16) build=91783b40…
     * ```
     */
    fun text(crash: Crash): String = buildString {
        append("signal=").append(crash.signal.ifEmpty { "?" })
        if (crash.code.isNotEmpty()) append(" code=").append(crash.code)
        append(" fault-address=").append(if (crash.hasFaultAddress) "yes" else "no")
        if (crash.abortMessage.isNotBlank()) {
            append("\nabort: ").append(scrubbed(crash.abortMessage))
        }
        append("\nthread \"").append(plainName(crash.thread)).append('"')
        crash.frames.forEachIndexed { index, frame ->
            append("\n  #").append(index.toString().padStart(2, '0'))
            append(" pc ").append(java.lang.Long.toHexString(frame.relPc).padStart(8, '0'))
            append(' ').append(baseName(frame.file).ifEmpty { "?" })
            if (frame.function.isNotEmpty()) {
                append(" (").append(frame.function.take(MAX_TEXT)).append('+').append(frame.offset).append(')')
            }
            if (frame.buildId.isNotEmpty()) append(" build=").append(frame.buildId.take(40))
        }
        if (crash.frames.isEmpty()) append("\n  (no frames)")
    }

    /** The abort message is a library's own text (an assertion, the allocator's complaint); links and addresses go anyway. */
    private fun scrubbed(message: String): String =
        SupportReport.withoutAddresses(LogPrivacy.redactLinks(message.replace('\n', ' ').trim())).take(MAX_TEXT)

    /** "/data/app/~~x==/com.vpn4tv.hiddify-y==/lib/arm64/libbox.so" → "libbox.so"; "base.apk!libbox.so" too. */
    internal fun baseName(file: String): String = file.substringAfterLast('/').substringAfterLast('!').take(80)

    private val PLAIN_NAME = Regex("""[A-Za-z0-9 _#-]+""")

    /** A thread's name that could hold a host name is not kept (as in an ANR's dump). */
    private fun plainName(name: String): String = if (PLAIN_NAME.matches(name)) name.take(40) else "thread"

    private fun read(bytes: ByteArray): Crash? {
        var tid = 0L
        var signal = ""
        var code = ""
        var hasFault = false
        var abort = ""
        val threads = ArrayList<Pair<Long, Reader>>()
        val top = Reader(bytes, 0, bytes.size)
        // A tombstone cut off at the reading limit ends inside a field: what was read before it stays.
        try {
            readTop(top, { tid = it }, { name, codeName, fault -> signal = name; code = codeName; hasFault = fault },
                { abort = it }, { id, thread -> threads.add(id to thread) })
        } catch (_: RuntimeException) {
        }
        // The thread that got the signal; a tombstone with one thread and no tid still has its stack.
        val crashed = threads.firstOrNull { it.first == tid }?.second
            ?: threads.singleOrNull()?.second
            ?: return null
        var name = ""
        val frames = ArrayList<Frame>()
        while (!crashed.done()) {
            val k = crashed.varint().toInt()
            when (k ushr 3) {
                2 -> name = crashed.string()
                4 -> {                                         // current_backtrace
                    val f = crashed.message()
                    var relPc = 0L
                    var function = ""
                    var offset = 0L
                    var file = ""
                    var build = ""
                    while (!f.done()) {
                        val fk = f.varint().toInt()
                        when (fk ushr 3) {
                            1 -> relPc = f.varint()
                            4 -> function = f.string()
                            5 -> offset = f.varint()
                            6 -> file = f.string()
                            8 -> build = f.string()
                            else -> f.skip(fk and 7)
                        }
                    }
                    if (frames.size < MAX_FRAMES) frames.add(Frame(relPc, function, offset, file, build))
                }
                else -> crashed.skip(k and 7)
            }
        }
        return Crash(signal, code, hasFault, abort, name, frames)
    }

    private fun readTop(
        top: Reader,
        onTid: (Long) -> Unit,
        onSignal: (String, String, Boolean) -> Unit,
        onAbort: (String) -> Unit,
        onThread: (Long, Reader) -> Unit,
    ) {
        while (!top.done()) {
            val key = top.varint().toInt()
            when (key ushr 3) {
                6 -> onTid(top.varint())                       // tid
                10 -> {                                        // signal_info
                    val s = top.message()
                    var name = ""
                    var codeName = ""
                    var fault = false
                    while (!s.done()) {
                        val k = s.varint().toInt()
                        when (k ushr 3) {
                            2 -> name = s.string()
                            4 -> codeName = s.string()
                            8 -> fault = s.varint() != 0L
                            else -> s.skip(k and 7)
                        }
                    }
                    onSignal(name, codeName, fault)
                }
                14 -> onAbort(top.string())                    // abort_message
                16 -> {                                        // threads: map<uint32, Thread>
                    val entry = top.message()
                    var id = 0L
                    var thread: Reader? = null
                    while (!entry.done()) {
                        val k = entry.varint().toInt()
                        when (k ushr 3) {
                            1 -> id = entry.varint()
                            2 -> thread = entry.message()
                            else -> entry.skip(k and 7)
                        }
                    }
                    thread?.let { onThread(id, it) }
                }
                else -> top.skip(key and 7)
            }
        }
    }

    /** A cursor over one protobuf message inside [b]. Throws a RuntimeException on anything malformed. */
    private class Reader(private val b: ByteArray, private var p: Int, private val end: Int) {
        fun done(): Boolean = p >= end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (p >= end) throw IndexOutOfBoundsException("varint past the end")
                val x = b[p++].toInt()
                result = result or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalStateException("varint too long")
            }
        }

        private fun length(): Int {
            val n = varint()
            if (n < 0 || n > end - p) throw IndexOutOfBoundsException("length past the end")
            return n.toInt()
        }

        fun message(): Reader {
            val n = length()
            return Reader(b, p, p + n).also { p += n }
        }

        fun string(): String {
            val n = length()
            return String(b, p, n, Charsets.UTF_8).also { p += n }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> advance(length())
                5 -> advance(4)
                else -> throw IllegalStateException("wire type $wire")
            }
        }

        private fun advance(n: Int) {
            if (n > end - p) throw IndexOutOfBoundsException("field past the end")
            p += n
        }
    }
}
