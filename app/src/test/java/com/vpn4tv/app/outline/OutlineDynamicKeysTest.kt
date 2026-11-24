package com.vpn4tv.app.outline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineDynamicKeysTest {

    private fun ports(json: String): List<Int> {
        val endpoints = JSONObject(json).getJSONArray("endpoints")
        return (0 until endpoints.length()).map { endpoints.getJSONObject(it).getInt("port") }
    }

    @Test
    fun `an endpoint whose dynamic key never came is left out, the others keep their ports`() {
        val sidecar = """{"endpoints":[
            {"dynamicUrl":"ssconf://keys.example/a","port":43890},
            {"url":"ss://YWVzLTI1Ni1nY206c2VjcmV0@1.2.3.4:443#t","port":43891},
            {"dynamicUrl":"ssconf://keys.example/b","url":"  ","port":43892}
        ]}"""
        assertEquals(listOf(43891), ports(OutlineDynamicKeys.dropUnresolved(sidecar)!!))
    }

    @Test
    fun `no endpoint with a key leaves nothing to start`() {
        assertNull(OutlineDynamicKeys.dropUnresolved("""{"endpoints":[{"dynamicUrl":"ssconf://keys.example/a","port":43890}]}"""))
    }

    @Test
    fun `a sidecar where every endpoint has a key is returned as it is`() {
        val sidecar = """{"endpoints":[{"dynamicUrl":"ssconf://keys.example/a","url":"ss://YWVzLTI1Ni1nY206c2VjcmV0@1.2.3.4:443#t","port":43890}]}"""
        assertEquals(sidecar, OutlineDynamicKeys.dropUnresolved(sidecar))
    }

    private val key = "ss://YWVzLTI1Ni1nY206c2VjcmV0@1.2.3.4:443#t"
    private val newKey = "ss://YWVzLTI1Ni1nY206bmV3@5.6.7.8:443#t"

    private fun urls(json: String): Map<Int, String> {
        val endpoints = JSONObject(json).getJSONArray("endpoints")
        return (0 until endpoints.length()).associate {
            val e = endpoints.getJSONObject(it)
            e.getInt("port") to e.optString("url")
        }
    }

    @Test
    fun `each dynamic key gets its outcome, by host and port, and a key that came is filled in`() {
        val sidecar = """{"endpoints":[
            {"dynamicUrl":"https://keys.example/secret-a","port":100},
            {"dynamicUrl":"https://blocked.example/secret-b","url":"$key","port":101},
            {"dynamicUrl":"https://blocked.example/secret-c","port":102},
            {"url":"$key","port":103}
        ]}"""
        val r = OutlineDynamicKeys.resolve(sidecar) { url ->
            if ("keys.example" in url) OutlineDynamicKeys.Fetch(newKey, null)
            else OutlineDynamicKeys.Fetch(null, "net/http: TLS handshake timeout")
        }
        assertEquals(listOf(100, 101, 102), r.outcomes.map { it.port })
        assertEquals(
            listOf(OutlineDynamicKeys.State.FETCHED, OutlineDynamicKeys.State.KEPT, OutlineDynamicKeys.State.NONE),
            r.outcomes.map { it.state },
        )
        assertEquals(listOf("keys.example", "blocked.example", "blocked.example"), r.outcomes.map { it.host })
        assertTrue(r.outcomes[0].changed)
        assertEquals("net/http: TLS handshake timeout", r.outcomes[2].reason)
        assertEquals(mapOf(100 to newKey, 101 to key, 102 to "", 103 to key), urls(r.sidecar))
        // The host only: never the path, which is the key.
        assertFalse(r.outcomes.any { "secret" in it.host })
    }

    @Test
    fun `a key that comes the same as before changes nothing`() {
        val sidecar = """{"endpoints":[{"dynamicUrl":"https://keys.example/a","url":"$key","port":100}]}"""
        val r = OutlineDynamicKeys.resolve(sidecar) { OutlineDynamicKeys.Fetch(key, null) }
        assertEquals(sidecar, r.sidecar)
        assertEquals(OutlineDynamicKeys.State.FETCHED, r.outcomes.single().state)
        assertFalse(r.outcomes.single().changed)
    }

    @Test
    fun `the keys are fetched at once and a slow one is given up after its own wait`() {
        val sidecar = """{"endpoints":[
            {"dynamicUrl":"https://slow.example/a","url":"$key","port":100},
            {"dynamicUrl":"https://slow.example/b","port":101}
        ]}"""
        val started = System.nanoTime()
        val r = OutlineDynamicKeys.resolve(sidecar, waitWithEarlierMs = 300, waitWithoutEarlierMs = 600) {
            Thread.sleep(5_000)
            OutlineDynamicKeys.Fetch(newKey, null)
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs in 550..2_000)
        assertEquals(listOf(OutlineDynamicKeys.State.KEPT, OutlineDynamicKeys.State.NONE), r.outcomes.map { it.state })
        assertEquals(listOf("no answer in 0 s", "no answer in 0 s"), r.outcomes.map { it.reason })
        assertEquals(sidecar, r.sidecar)
    }

    @Test
    fun `only the ports asked for are fetched`() {
        val sidecar = """{"endpoints":[
            {"dynamicUrl":"https://keys.example/a","port":100},
            {"dynamicUrl":"https://keys.example/b","port":101}
        ]}"""
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        val r = OutlineDynamicKeys.resolve(sidecar, onlyPorts = setOf(101)) {
            asked.add(it)
            OutlineDynamicKeys.Fetch(newKey, null)
        }
        assertEquals(listOf("https://keys.example/b"), asked.toList())
        assertEquals(mapOf(100 to "", 101 to newKey), urls(r.sidecar))
    }

    @Test
    fun `a failure is told without the link`() {
        assertEquals(
            "net/http: TLS handshake timeout",
            OutlineDynamicKeys.shortReason("Get \"https://keys.example/secret\": net/http: TLS handshake timeout"),
        )
        assertEquals("HTTP 404 Not Found", OutlineDynamicKeys.shortReason("HTTP 404 Not Found: <html>gone</html>"))
        assertFalse("secret" in OutlineDynamicKeys.shortReason("dial https://keys.example/secret failed"))
        assertEquals("unknown error", OutlineDynamicKeys.shortReason(null))
        assertEquals(
            "read tcp <address>:34654-><address>:443: read: connection reset by peer",
            OutlineDynamicKeys.shortReason("read tcp 203.0.113.7:34654->198.51.100.13:443: read: connection reset by peer"),
        )
    }
}
