package com.vpn4tv.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Android's tombstone, written here field by field as debuggerd's tombstone.proto numbers them. */
class NativeTombstoneTest {

    private class Proto {
        val out = ByteArrayOutputStream()

        fun varint(value: Long): Proto {
            var v = value
            while (v and 0x7fL.inv() != 0L) {
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
            return this
        }

        fun number(field: Int, value: Long): Proto = varint((field.toLong() shl 3)).varint(value)

        fun bytes(field: Int, value: ByteArray): Proto {
            varint((field.toLong() shl 3) or 2).varint(value.size.toLong())
            out.write(value)
            return this
        }

        fun text(field: Int, value: String): Proto = bytes(field, value.toByteArray())
        fun message(field: Int, value: Proto): Proto = bytes(field, value.out.toByteArray())
        fun fixed64(field: Int): Proto {
            varint((field.toLong() shl 3) or 1)
            repeat(8) { out.write(0x11) }
            return this
        }
    }

    private fun frame(relPc: Long, function: String, offset: Long, file: String, build: String) = Proto()
        .number(1, relPc).number(2, 0x7000000000L + relPc).number(3, 0x7ffff000L)
        .text(4, function).number(5, offset).text(6, file).number(7, 0).text(8, build)

    private fun thread(id: Long, name: String, vararg frames: Proto): Proto {
        val t = Proto().number(1, id).text(2, name)
            // registers, a memory dump: skipped
            .message(3, Proto().text(1, "x0").number(2, 0x1234))
        frames.forEach { t.message(4, it) }
        return Proto().number(1, id).message(2, t)
    }

    private val lib = "/data/app/~~SHlnvynwKZ3YydJUvGa55Q==/com.vpn4tv.hiddify-afPTXC34iNXzZqVP5ajNjw==/lib/arm64/libtrusttunnel_android.so"

    /** The crash of 23 November 2026 on the emulator: the stack Google Play has for the same bug. */
    private fun tombstone() = Proto()
        .number(1, 3).text(2, "google/sdk_gphone64_arm64/emu64a:14/UE1A/1:userdebug/dev-keys").text(4, "2026-11-23 08:55:08")
        .number(5, 26961).number(6, 27226).number(7, 10192)
        .text(9, "com.vpn4tv.hiddify")
        .message(10, Proto().number(1, 11).text(2, "SIGSEGV").number(3, 2).text(4, "SEGV_ACCERR").number(8, 1).fixed64(9))
        .message(16, thread(26961, "main", frame(0xa1b2c, "__epoll_pwait", 8, "/apex/com.android.runtime/lib64/bionic/libc.so", "aa")))
        .message(
            16,
            thread(
                27226, "Thread-30",
                frame(0x8436c0, "bufferevent_openssl_get_ssl", 16, lib, "91783b40889691393edfb0b31b7be4c1cc6940b6"),
                frame(0x5f1640, "ag::tcp_socket_destroy(ag::TcpSocket*)", 152, lib, "91783b40889691393edfb0b31b7be4c1cc6940b6"),
                frame(0x61b458, "ag::PingConn::~PingConn()", 44, lib, "91783b40889691393edfb0b31b7be4c1cc6940b6"),
                frame(0x61a620, "", 0, lib, "91783b40889691393edfb0b31b7be4c1cc6940b6"),
                frame(0xcb6a8, "__pthread_start(void*)", 208, "/apex/com.android.runtime/lib64/bionic/libc.so", "bb"),
            ),
        )
        // the memory map, the log, the open files: at the end, never read
        .message(17, Proto().number(1, 0x7000000000L).text(8, "/system/bin/app_process64"))
        .message(18, Proto().text(1, "main").message(2, Proto().text(8, "connecting to 203.0.113.7")))
        .out.toByteArray()

    @Test
    fun theSignalAndTheStackOfTheThreadThatCrashed() {
        val crash = NativeTombstone.parse(tombstone())
        assertNotNull(crash)
        val text = NativeTombstone.text(crash!!)
        assertEquals(
            """
            signal=SIGSEGV code=SEGV_ACCERR fault-address=yes
            thread "Thread-30"
              #00 pc 008436c0 libtrusttunnel_android.so (bufferevent_openssl_get_ssl+16) build=91783b40889691393edfb0b31b7be4c1cc6940b6
              #01 pc 005f1640 libtrusttunnel_android.so (ag::tcp_socket_destroy(ag::TcpSocket*)+152) build=91783b40889691393edfb0b31b7be4c1cc6940b6
              #02 pc 0061b458 libtrusttunnel_android.so (ag::PingConn::~PingConn()+44) build=91783b40889691393edfb0b31b7be4c1cc6940b6
              #03 pc 0061a620 libtrusttunnel_android.so build=91783b40889691393edfb0b31b7be4c1cc6940b6
              #04 pc 000cb6a8 libc.so (__pthread_start(void*)+208) build=bb
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun nothingOfTheLogTheMapOrTheInstallPathIsKept() {
        val text = NativeTombstone.text(NativeTombstone.parse(tombstone())!!)
        assertTrue("203.0.113.7" !in text)
        assertTrue("app_process" !in text)
        assertTrue("/data/app" !in text)
        assertTrue("main" !in text)
    }

    @Test
    fun anAbortMessageLosesLinksAndAddresses() {
        val bytes = Proto().number(6, 7)
            .message(10, Proto().number(1, 6).text(2, "SIGABRT").text(4, "SI_TKILL"))
            .text(14, "assertion failed at https://example.com/a/b?key=secret for 203.0.113.7\nline two")
            .message(16, thread(7, "OkHttp example.com", frame(0x10, "abort", 164, "/apex/com.android.runtime/lib64/bionic/libc.so", "")))
            .out.toByteArray()
        val text = NativeTombstone.text(NativeTombstone.parse(bytes)!!)
        assertTrue(text.startsWith("signal=SIGABRT code=SI_TKILL fault-address=no\nabort: assertion failed at "))
        assertTrue("secret" !in text)
        assertTrue("203.0.113.7" !in text)
        assertTrue("line two" in text)
        // A thread's name that could hold a host name is not kept.
        assertTrue("thread \"thread\"" in text)
        assertTrue("#00 pc 00000010 libc.so (abort+164)" in text)
    }

    @Test
    fun aTombstoneCutOffAfterTheThreadsStillGivesTheStack() {
        val whole = tombstone()
        // Inside the memory map, the field after the threads.
        val cut = whole.copyOf(whole.size - 40)
        val crash = NativeTombstone.parse(cut)
        assertNotNull(crash)
        assertEquals(5, crash!!.frames.size)
    }

    @Test
    fun whatIsNotATombstoneGivesNothing() {
        assertNull(NativeTombstone.parse(ByteArray(0)))
        assertNull(NativeTombstone.parse("----- pid 123 at 2026-11-23 -----\nCmd line: com.vpn4tv.hiddify\n".toByteArray()))
        // A thread list without the crashed thread among several.
        val bytes = Proto().number(6, 99).message(16, thread(1, "a")).message(16, thread(2, "b")).out.toByteArray()
        assertNull(NativeTombstone.parse(bytes))
    }

    @Test
    fun aLibraryInsideTheApkIsNamedByItsFile() {
        assertEquals("libbox.so", NativeTombstone.baseName("/data/app/x/base.apk!libbox.so"))
        assertEquals("libc.so", NativeTombstone.baseName("/apex/com.android.runtime/lib64/bionic/libc.so"))
        assertEquals("", NativeTombstone.baseName(""))
    }
}
