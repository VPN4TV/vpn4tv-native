package com.vpn4tv.app.outline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
