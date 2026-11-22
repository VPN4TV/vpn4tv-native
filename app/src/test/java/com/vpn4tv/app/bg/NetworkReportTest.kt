package com.vpn4tv.app.bg

import com.vpn4tv.app.bg.NetworkReport.Facts
import com.vpn4tv.app.bg.NetworkReport.Routes
import com.vpn4tv.app.bg.NetworkReport.Way
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReportTest {
    private val wifi = Facts("wlan0", 16, listOf("wifi"), internet = true, validated = true, v4Route = true, v6Route = true)

    @Test
    fun aNetworkInWords() {
        assertEquals("wlan0 (16) wifi, validated, routes v4+v6", NetworkReport.describe(wifi))
        assertEquals(
            "eth0 (3) ethernet, not validated, routes v4",
            NetworkReport.describe(Facts("eth0", 3, listOf("ethernet"), internet = true, validated = false, v4Route = true, v6Route = false)),
        )
        assertEquals(
            "rmnet0 cellular, validated, routes v6 only",
            NetworkReport.describe(Facts("rmnet0", -1, listOf("cellular"), internet = true, validated = true, v4Route = false, v6Route = true)),
        )
        assertEquals(
            "? unknown, no internet access, no default route",
            NetworkReport.describe(Facts(null, -1, emptyList(), internet = false, validated = false, v4Route = false, v6Route = false)),
        )
    }

    @Test
    fun aVpnIsOnlyNamed() {
        assertEquals(
            "tun1 (27) vpn",
            NetworkReport.describe(Facts("tun1", 27, listOf("vpn"), internet = true, validated = true, v4Route = true, v6Route = true)),
        )
    }

    @Test
    fun theLineNamesTheChosenNetworkAndTheOthers() {
        val tun = Facts("tun1", 27, listOf("vpn"), internet = true, validated = true, v4Route = true, v6Route = true)
        assertEquals("network: wlan0 (16) wifi, validated, routes v4+v6", NetworkReport.line(wifi, emptyList()))
        assertEquals("network: wlan0 (16) wifi, validated, routes v4+v6; also tun1 (27) vpn", NetworkReport.line(wifi, listOf(tun)))
        assertEquals("network: none; also tun1 (27) vpn", NetworkReport.line(null, listOf(tun)))
        assertEquals(
            "network: none (Android no longer knows the network)",
            NetworkReport.line(null, emptyList(), "Android no longer knows the network"),
        )
    }

    @Test
    fun noNetworkAndNoIpv4RouteAreTrouble() {
        assertFalse(NetworkReport.troubled(wifi))
        assertTrue(NetworkReport.troubled(null))
        assertTrue(NetworkReport.troubled(wifi.copy(v4Route = false)))
        // Not validated is what Android thinks of the internet behind it: the core tries anyway.
        assertFalse(NetworkReport.troubled(wifi.copy(validated = false)))
    }

    @Test
    fun aNetworksNumberAndWhatSetsItApart() {
        assertEquals(
            "wlan0 (16) net 100 wifi, validated, routes v4+v6",
            NetworkReport.describe(wifi.copy(net = "100")),
        )
        assertEquals(
            "eth0 (3) net 101 ethernet, not validated, routes v4, restricted, in the background",
            NetworkReport.describe(
                Facts(
                    "eth0", 3, listOf("ethernet"), internet = true, validated = false, v4Route = true, v6Route = false,
                    net = "101", marks = listOf("restricted", "in the background"),
                ),
            ),
        )
    }

    @Test
    fun aVpnSaysWhoseItIs() {
        val tun = Facts("tun0", 27, listOf("vpn"), internet = true, validated = true, v4Route = true, v6Route = true, net = "102")
        assertEquals("tun0 (27) net 102 vpn, this app's", NetworkReport.describe(tun.copy(ours = true)))
        assertEquals("tun0 (27) net 102 vpn, not this app's", NetworkReport.describe(tun.copy(ours = false)))
        // Before Android 11 the system does not say, and neither does the line. Its marks are no one's business.
        assertEquals("tun0 (27) net 102 vpn", NetworkReport.describe(tun.copy(marks = listOf("metered"))))
    }

    @Test
    fun everyNetworkOfTheDeviceInOneLine() {
        val tun = Facts("tun0", 27, listOf("vpn"), internet = true, validated = true, v4Route = true, v6Route = true, ours = true)
        assertEquals("networks: none", NetworkReport.networksLine(emptyList()))
        assertEquals(
            "networks: wlan0 (16) wifi, validated, routes v4+v6; tun0 (27) vpn, this app's",
            NetworkReport.networksLine(listOf(wifi, tun)),
        )
    }

    private val ok = Routes(Way("ok"), Way("ok"))
    private val none = Routes(Way("ENETUNREACH"), Way("ENETUNREACH"))

    @Test
    fun theRouteLine() {
        assertEquals("routes: plain v4 ok, v6 ok", NetworkReport.routeLine(ok, null, emptyList(), null))
        assertEquals(
            "routes: plain v4 ok, v6 ok; protected v4 ENETUNREACH, v6 ENETUNREACH; bound to wlan0 v4 ok, v6 ENETUNREACH",
            NetworkReport.routeLine(ok, none, listOf("wlan0" to Routes(Way("ok"), Way("ENETUNREACH"))), null),
        )
    }

    @Test
    fun theRouteLineNamesTheInterfaceTheKernelChose() {
        val throughTun = Routes(Way("ok", "tun0"), Way("ok", "tun0"))
        val byWifi = Routes(Way("ok", "wlan0"), Way("ENETUNREACH"))
        assertEquals(
            "routes: plain v4 ok via tun0, v6 ok via tun0; protected v4 ok via wlan0, v6 ENETUNREACH" +
                // A socket tied to a network leaves by its interface: said only when it does not.
                "; bound to wlan0 v4 ok, v6 ENETUNREACH; bound to eth0 v4 ok via wlan0, v6 ENETUNREACH" +
                "; kernel bind to wlan0: EPERM",
            NetworkReport.routeLine(throughTun, byWifi, listOf("wlan0" to byWifi, "eth0" to byWifi), "wlan0: EPERM"),
        )
    }

    @Test
    fun theKernelsInterfacesByKindOfAddress() {
        fun kind(address: String) = NetworkReport.addressKind(InetAddress.getByName(address))
        assertEquals("v4 private", kind("192.168.1.20"))
        assertEquals("v4 private", kind("10.8.0.2"))
        assertEquals("v4 private", kind("172.19.0.1"))
        assertEquals("v4 provider's private", kind("100.64.3.4"))
        assertEquals("v4 public", kind("100.128.0.1"))
        assertEquals("v4 public", kind("8.8.8.8"))
        assertEquals("v4 link-local", kind("169.254.10.1"))
        assertEquals("v6 link-local", kind("fe80::1"))
        assertEquals("v6 local", kind("fdfe:dcba:9876::1"))
        assertEquals("v6 global", kind("2a00:1450:4010:c0b::8a"))

        assertEquals(
            "wlan0 (16) up: v4 private, v6 global, v6 link-local",
            NetworkReport.describeInterface("wlan0", 16, true, listOf("v6 link-local", "v4 private", "v6 global", "v6 global")),
        )
        assertEquals("eth0 (3) down: no address", NetworkReport.describeInterface("eth0", 3, false, emptyList()))
        assertEquals("ppp0: v4 public", NetworkReport.describeInterface("ppp0", -1, null, listOf("v4 public")))
    }

    @Test
    fun theInterfacesTheCoreWasGiven() {
        val list = listOf("wlan0 (16) wifi", "tun0 (27) vpn, not up for the core")
        assertEquals(
            "interfaces given to the core: wlan0 (16) wifi; tun0 (27) vpn, not up for the core" to false,
            NetworkReport.givenLine(list, setOf(16, 27), "wlan0" to 16),
        )
        // Told none: nothing to miss.
        assertEquals("interfaces given to the core: none" to false, NetworkReport.givenLine(emptyList(), emptySet(), null))
        // The default the core was told is not in the list it reads: it cannot take it and goes on without one.
        assertEquals(
            "interfaces given to the core: tun0 (27) vpn — eth0 (3), the default it was told, is not among them" to true,
            NetworkReport.givenLine(listOf("tun0 (27) vpn"), setOf(27), "eth0" to 3),
        )
    }

    @Test
    fun theErrorNameOutOfTheSystemsWords() {
        assertEquals("ENETUNREACH", NetworkReport.errnoIn("connect failed: ENETUNREACH (Network is unreachable)"))
        assertEquals("EPERM", NetworkReport.errnoIn("failed to connect to /192.0.2.1 (port 443): connect failed: EPERM (Operation not permitted)"))
        assertEquals("Socket closed", NetworkReport.errnoIn("Socket closed"))
        assertEquals("failed", NetworkReport.errnoIn(null))
    }
}
