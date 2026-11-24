package com.vpn4tv.app.bg

import com.vpn4tv.app.bg.OutlineKeyCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class OutlineKeyCheckTest {

    /** Two Outline servers on ports 100 and 101, and a server of another kind. */
    private val config = """{"outbounds":[
        {"type":"urltest","tag":"auto","outbounds":["blocked","outline-ok","vless"]},
        {"type":"selector","tag":"select","outbounds":["auto","blocked","outline-ok","vless"],"default":"auto"},
        {"type":"socks","tag":"blocked","server":"127.0.0.127","server_port":100},
        {"type":"socks","tag":"outline-ok","server":"127.0.0.127","server_port":101},
        {"type":"vless","tag":"vless","server":"example.com","server_port":443},
        {"type":"direct","tag":"direct"}
    ]}"""

    private val single = """{"outbounds":[
        {"type":"urltest","tag":"auto","outbounds":["blocked"]},
        {"type":"selector","tag":"select","outbounds":["auto","blocked"],"default":"auto"},
        {"type":"socks","tag":"blocked","server":"127.0.0.127","server_port":100},
        {"type":"direct","tag":"direct"}
    ]}"""

    @Test
    fun `nothing left out, nothing to say`() {
        assertEquals(Verdict.GO_ON, OutlineKeyCheck.verdict(single, emptySet(), "blocked"))
    }

    @Test
    fun `the only server left out stops the start, chosen or on auto`() {
        assertEquals(Verdict.NO_OTHER_SERVER, OutlineKeyCheck.verdict(single, setOf(100), null))
        assertEquals(Verdict.NO_OTHER_SERVER, OutlineKeyCheck.verdict(single, setOf(100), "auto"))
        assertEquals(Verdict.NO_OTHER_SERVER, OutlineKeyCheck.verdict(single, setOf(100), "blocked"))
    }

    @Test
    fun `on auto with other servers the start goes on`() {
        assertEquals(Verdict.GO_ON, OutlineKeyCheck.verdict(config, setOf(100), null))
        assertEquals(Verdict.GO_ON, OutlineKeyCheck.verdict(config, setOf(100, 101), "auto"))
    }

    @Test
    fun `the chosen server left out asks for another`() {
        assertEquals(Verdict.PICK_ANOTHER, OutlineKeyCheck.verdict(config, setOf(100), "blocked"))
    }

    @Test
    fun `another server chosen goes on`() {
        assertEquals(Verdict.GO_ON, OutlineKeyCheck.verdict(config, setOf(100), "vless"))
        assertEquals(Verdict.GO_ON, OutlineKeyCheck.verdict(config, setOf(100), "outline-ok"))
    }

    @Test
    fun `a saved server the profile no longer has counts as auto`() {
        assertEquals(Verdict.GO_ON, OutlineKeyCheck.verdict(config, setOf(100), "gone"))
        assertEquals(Verdict.NO_OTHER_SERVER, OutlineKeyCheck.verdict(single, setOf(100), "gone"))
    }

    @Test
    fun `a port of another bridge is not taken for an Outline one`() {
        val other = config.replace("\"server\":\"127.0.0.127\",\"server_port\":100", "\"server\":\"example.org\",\"server_port\":100")
        assertEquals(Verdict.GO_ON, OutlineKeyCheck.verdict(other, setOf(100), "blocked"))
    }
}
