package com.vpn4tv.app.bg

import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.vpn4tv.app.Application
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * The device's networks in words, for support (SupportNotes, the Logs screen,
 * "Send to support"): the network the core was told to go out by, the others
 * Android has, the kernel's interfaces, and whether the app's sockets get a
 * route at all and by which interface.
 *
 * Why: the core's connections leave past the VPN by VpnService.protect and
 * the system's default network. When Android has no default network for them
 * every connection of the core fails at once with "connect: network is
 * unreachable", the DNS and the server alike, while the VPN shows as
 * connected (support case of 2026-11-21, a Xiaomi TV on Android 12). The
 * core's log says nothing of what the device reported; these lines do.
 *
 * No addresses, no Wi-Fi name: interface names, kinds of network and of
 * address, and what Android says about them.
 */
object NetworkReport {
    /** What Android says about one network. */
    internal data class Facts(
        val iface: String?,
        val index: Int,
        val transports: List<String>,
        val internet: Boolean,
        val validated: Boolean,
        val v4Route: Boolean,
        val v6Route: Boolean,
        /** Android's own number of the network (Network.toString): the same network keeps it, a reconnect gets a new one. */
        val net: String? = null,
        /** What sets it apart from an ordinary network: "restricted", "metered", "in the background", "nat64"… */
        val marks: List<String> = emptyList(),
        /** A VPN: whether it is this app's. Null when Android does not say (before Android 11). */
        val ours: Boolean? = null,
    )

    /** "wlan0 (16) net 100 wifi, validated, routes v4+v6"; of a VPN's network only whose it is. */
    internal fun describe(f: Facts): String = buildString {
        append(f.iface?.takeIf { it.isNotEmpty() } ?: "?")
        if (f.index > 0) append(" (").append(f.index).append(')')
        if (f.net != null) append(" net ").append(f.net)
        append(' ').append(if (f.transports.isEmpty()) "unknown" else f.transports.joinToString("+"))
        if ("vpn" in f.transports) {
            when (f.ours) {
                true -> append(", this app's")
                false -> append(", not this app's")
                null -> {}
            }
            return@buildString
        }
        append(", ").append(
            when {
                !f.internet -> "no internet access"
                f.validated -> "validated"
                else -> "not validated"
            },
        )
        append(", ").append(
            when {
                f.v4Route && f.v6Route -> "routes v4+v6"
                f.v4Route -> "routes v4"
                f.v6Route -> "routes v6 only"
                else -> "no default route"
            },
        )
        f.marks.forEach { append(", ").append(it) }
    }

    /**
     * One line: the network the core was told ([chosen]; null, none) and the
     * device's other networks. [problem]: why there is none, when known.
     */
    internal fun line(chosen: Facts?, others: List<Facts>, problem: String? = null): String = buildString {
        append("network: ")
        if (chosen != null) append(describe(chosen)) else append("none")
        if (problem != null) append(" (").append(problem).append(')')
        if (others.isNotEmpty()) append("; also ").append(others.joinToString("; ") { describe(it) })
    }

    /** Every network Android has, the VPN's too: the line NetworkWatch writes each time the set changes. */
    internal fun networksLine(all: List<Facts>): String =
        "networks: " + if (all.isEmpty()) "none" else all.joinToString("; ") { describe(it) }

    /** Whether the line tells of trouble: no network, or one without an IPv4 route (our servers are IPv4). */
    internal fun troubled(chosen: Facts?): Boolean = chosen == null || !chosen.v4Route

    private val transportNames = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "wifi",
        NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
        NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
        NetworkCapabilities.TRANSPORT_VPN to "vpn",
        NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
        8 to "usb", // TRANSPORT_USB, Android 12
    )

    /**
     * What sets a network apart from an ordinary one, out of its capabilities:
     * Android makes a network the system's default (the one the VPN's own
     * connections leave by) only when it is neither restricted nor untrusted.
     */
    @android.annotation.SuppressLint("WrongConstant")
    internal fun marks(caps: NetworkCapabilities): List<String> {
        val marks = ArrayList<String>()
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) marks.add("restricted")
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)) marks.add("not trusted")
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) marks.add("metered")
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) marks.add("captive portal")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) marks.add("suspended")
            // Not the network of any app in front: kept for a while after another took its place, or never chosen.
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)) marks.add("in the background")
        }
        // A network the firmware keeps for chosen apps (Android 12 can give apps a default network of their own,
        // apart from the system's): by number, the names are not public on every version.
        if (caps.hasCapability(22)) marks.add("oem paid") // NET_CAPABILITY_OEM_PAID
        if (caps.hasCapability(26)) marks.add("oem private") // NET_CAPABILITY_OEM_PRIVATE
        if (caps.hasCapability(29)) marks.add("enterprise") // NET_CAPABILITY_ENTERPRISE
        return marks
    }

    /**
     * Null when Android no longer knows the network. [link] and [index]: its
     * link properties and its interface's index, when the caller has just
     * read them (a call to the system each).
     */
    internal fun facts(
        network: Network,
        link: LinkProperties? = Application.connectivity.getLinkProperties(network),
        index: Int? = null,
    ): Facts? = try {
        val caps: NetworkCapabilities? = Application.connectivity.getNetworkCapabilities(network)
        if (link == null && caps == null) {
            null
        } else {
            val name = link?.interfaceName
            val marks = ArrayList<String>()
            if (caps != null) marks.addAll(marks(caps))
            if (link != null) {
                if (link.dnsServers.isEmpty()) marks.add("no dns")
                // An IPv6-only network: IPv4 goes through a translator on the device (464XLAT).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && link.nat64Prefix != null) marks.add("nat64")
            }
            val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            Facts(
                iface = name,
                index = index ?: name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() } ?: -1,
                transports = transportNames.filter { (id, _) -> caps?.hasTransport(id) == true }.map { it.second },
                internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                v4Route = link?.routes.orEmpty().any { defaultRoute(it) && it.destination.address is Inet4Address },
                v6Route = link?.routes.orEmpty().any { defaultRoute(it) && it.destination.address is Inet6Address },
                net = network.toString(),
                marks = marks,
                // Android names a network's owner to the owner alone.
                ours = if (vpn && caps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    caps.ownerUid == android.os.Process.myUid()
                } else {
                    null
                },
            )
        }
    } catch (e: Exception) {
        null
    }

    // Android 13 lists routes that lead nowhere too (unreachable, throw): not a way out.
    private fun defaultRoute(route: RouteInfo): Boolean =
        route.isDefaultRoute && (Build.VERSION.SDK_INT < 33 || route.type == RouteInfo.RTN_UNICAST)

    /** Every network Android has, in the order of their numbers. */
    internal fun allNetworks(): List<Pair<Network, Facts>> = try {
        @Suppress("DEPRECATION")
        Application.connectivity.allNetworks
            .mapNotNull { network -> facts(network)?.let { network to it } }
            .sortedBy { it.second.net?.toIntOrNull() ?: Int.MAX_VALUE }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * The line for [chosen] as it is now, with every other network of the
     * device, and whether it tells of trouble. [known]: its facts, when the
     * caller has just read them.
     */
    internal fun lineFor(
        chosen: Network?,
        problem: String? = null,
        known: Facts? = chosen?.let { facts(it) },
    ): Pair<String, Boolean> {
        val facts = known
        val others = allNetworks().filter { it.first != chosen }.map { it.second }
        val why = problem ?: if (chosen != null && facts == null) "Android no longer knows the network" else null
        return line(facts, others, why) to troubled(facts)
    }

    // ---- the kernel's interfaces

    /**
     * The kernel's interfaces that have an address, loopback aside:
     * "wlan0 (16) up: v4 private, v6 link-local". An interface that carries
     * the internet but is no network of Android's (a provider's PPPoE set up
     * by the firmware, a second cable) shows here only.
     */
    fun interfacesLine(): String = try {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { !runCatching { it.isLoopback }.getOrDefault(false) }
            .sortedBy { it.index }
            .mapNotNull { iface ->
                val up = runCatching { iface.isUp }.getOrNull()
                val kinds = iface.interfaceAddresses.mapNotNull { it?.address }.map(::addressKind)
                // Old Androids list the kernel's idle tunnel devices too (tunl0, sit0…): down and without an address.
                if (up == false && kinds.isEmpty()) null else describeInterface(iface.name, iface.index, up, kinds)
            }
        "interfaces: " + if (all.isEmpty()) "none" else all.joinToString("; ")
    } catch (e: Exception) {
        "interfaces: not read (${e.javaClass.simpleName})"
    }

    internal fun describeInterface(name: String?, index: Int, up: Boolean?, kinds: List<String>): String = buildString {
        append(name?.takeIf { it.isNotEmpty() } ?: "?")
        if (index > 0) append(" (").append(index).append(')')
        when (up) {
            true -> append(" up")
            false -> append(" down")
            null -> {}
        }
        append(": ").append(if (kinds.isEmpty()) "no address" else kinds.distinct().sorted().joinToString(", "))
    }

    /** The kind of an address, never the address: "v4 private", "v4 public", "v6 global", "v6 link-local"… */
    internal fun addressKind(address: InetAddress): String {
        val bytes = address.address
        return if (address is Inet4Address) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            when {
                address.isLoopbackAddress -> "v4 loopback"
                address.isLinkLocalAddress -> "v4 link-local" // 169.254/16: no DHCP answer
                address.isSiteLocalAddress -> "v4 private"
                first == 100 && second in 64..127 -> "v4 provider's private" // 100.64/10, carrier-grade NAT
                else -> "v4 public"
            }
        } else {
            when {
                address.isLoopbackAddress -> "v6 loopback"
                address.isLinkLocalAddress -> "v6 link-local"
                (bytes[0].toInt() and 0xfe) == 0xfc -> "v6 local" // fc00::/7
                else -> "v6 global"
            }
        }
    }

    /**
     * The interfaces the core was given (PlatformInterface.getInterfaces), as
     * a line. [described]: each as "wlan0 (16) wifi"; [indices]: theirs;
     * [told]: the default interface the core was last told, null for none.
     * The second value: the default it was told is not among them, so the core
     * could not take it ("find updated interface" in its log) and goes on
     * without a default interface: it then tries every interface it was given,
     * tied to each by the kernel.
     */
    internal fun givenLine(described: List<String>, indices: Set<Int>, told: Pair<String, Int>?): Pair<String, Boolean> {
        val missing = told != null && told.second !in indices
        val line = "interfaces given to the core: " + (if (described.isEmpty()) "none" else described.joinToString("; ")) +
            if (missing) " — ${told!!.first} (${told.second}), the default it was told, is not among them" else ""
        return line to missing
    }

    // ---- whether the app's sockets get a route

    // Documentation addresses (RFC 5737, RFC 3849): routed as any public address, owned by nobody.
    private val probeV4 = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)), 443)
    private val probeV6 = InetSocketAddress(
        InetAddress.getByAddress(byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)),
        443,
    )

    /** How one probe ended: [result] is [OK] or the system's error; [via]: the interface the kernel chose, when it can be told. */
    internal data class Way(val result: String, val via: String? = null)

    /** What the route check found for one kind of socket. */
    internal data class Routes(val v4: Way, val v6: Way)

    private const val MAX_BOUND = 4

    /**
     * Whether this app's sockets have a route, the way the kernel answers a
     * UDP connect(): nothing is sent. "plain": a socket as any of the app's
     * (inside the VPN while it is up); "protected": one taken out of the VPN
     * as the core's are (VpnService.protect), only while the VPN service
     * exists, and so one that leaves by the system's default network;
     * "bound to": one tied to a network of Android's (Network.bindSocket),
     * [network] first, by default the one the core was told, then every other
     * network that is no VPN; "kernel bind": one tied to [network]'s
     * interface by the kernel itself, as the core ties a connection to an
     * interface that is not its default. "via": the interface the kernel
     * chose, when it is not the one named. The second value is true when the
     * VPN's own connections have no IPv4 route.
     */
    fun routeCheck(network: Network? = DefaultNetworkMonitor.defaultNetwork): Pair<String, Boolean> {
        val plain = probe { true }
        val protected = if (VPNService.isRunning()) probe { VPNService.protectSocket(it) == true } else null
        val targets = bindTargets(network)
        val bound = targets.map { (target, name) -> name to probe { target.bindSocket(it); true } }
        val kernel = targets.firstOrNull()?.second?.let { name -> "$name: ${kernelBind(name)}" }
        return routeLine(plain, protected, bound, kernel) to (protected != null && protected.v4.result != OK)
    }

    internal const val OK = "ok"

    internal fun routeLine(plain: Routes, protected: Routes?, bound: List<Pair<String, Routes>>, kernel: String?): String = buildString {
        append("routes: plain ").append(show(plain, null))
        if (protected != null) append("; protected ").append(show(protected, null))
        for ((name, routes) in bound) append("; bound to ").append(name).append(' ').append(show(routes, name))
        if (kernel != null) append("; kernel bind to ").append(kernel)
    }

    private fun show(routes: Routes, expected: String?): String = "v4 ${show(routes.v4, expected)}, v6 ${show(routes.v6, expected)}"

    private fun show(way: Way, expected: String?): String =
        if (way.result == OK && way.via != null && way.via != expected) "$OK via ${way.via}" else way.result

    /** [chosen] first, then every other network that is no VPN; each with its interface's name. */
    private fun bindTargets(chosen: Network?): List<Pair<Network, String>> {
        val targets = ArrayList<Pair<Network, String>>()
        fun name(network: Network): String =
            runCatching { Application.connectivity.getLinkProperties(network)?.interfaceName }.getOrNull() ?: "?"
        if (chosen != null) targets.add(chosen to name(chosen))
        for ((network, facts) in allNetworks()) {
            if (targets.size >= MAX_BOUND) break
            if (network == chosen || "vpn" in facts.transports) continue
            targets.add(network to (facts.iface ?: "?"))
        }
        return targets
    }

    private fun probe(prepare: (DatagramSocket) -> Boolean): Routes {
        fun one(target: InetSocketAddress): Way = try {
            DatagramSocket().use { socket ->
                if (prepare(socket)) {
                    socket.connect(target)
                    Way(OK, via(socket))
                } else {
                    Way("refused")
                }
            }
        } catch (e: Exception) {
            Way(failure(e))
        }
        return Routes(one(probeV4), one(probeV6))
    }

    // The interface of the address the kernel gave the socket for this destination.
    private fun via(socket: DatagramSocket): String? = try {
        socket.localAddress?.takeUnless { it.isAnyLocalAddress }?.let { NetworkInterface.getByInetAddress(it)?.name }
    } catch (e: Exception) {
        null
    }

    private const val SO_BINDTOIFINDEX = 62 // Linux 5.1; not among OsConstants

    /**
     * Whether the kernel lets this app tie a socket to an interface by itself
     * (SO_BINDTOIFINDEX; without that option, SO_BINDTODEVICE), in the core's
     * order (sing common/control/bind_linux.go): the core does it for a
     * connection through an interface that is not the default one it was
     * told, or through any when it was told none. Kernels before 5.7 refuse
     * it to an app (EPERM), and the core then stops choosing interfaces for
     * that outbound for good and leaves by the system's default network alone
     * (sing-box common/dialer/default.go, "bind interface failed on legacy
     * xiaomi systems"): its errors lose the "dial wlan0 (16):" in front.
     * "ok" plus the routes when the kernel allows it.
     */
    private fun kernelBind(name: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "not asked (Android ${Build.VERSION.RELEASE})"
        val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: return "no such interface"
        var byName = false
        fun one(target: InetSocketAddress): String = try {
            DatagramSocket().use { socket ->
                VPNService.protectSocket(socket)
                ParcelFileDescriptor.fromDatagramSocket(socket).use { descriptor ->
                    try {
                        Os.setsockoptInt(descriptor.fileDescriptor, OsConstants.SOL_SOCKET, SO_BINDTOIFINDEX, index)
                    } catch (e: ErrnoException) {
                        if (e.errno != OsConstants.ENOPROTOOPT && e.errno != OsConstants.EINVAL) throw e
                        // No such option before Linux 5.1: by name then. The kernel asks for the right to bind
                        // before it reads the name, so an empty one answers the question and ties nothing.
                        Os.setsockoptInt(descriptor.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_BINDTODEVICE, 0)
                        byName = true
                    }
                }
                if (byName) {
                    "allowed"
                } else {
                    socket.connect(target)
                    show(Way(OK, via(socket)), name)
                }
            }
        } catch (e: Exception) {
            failure(e)
        }
        val v4 = one(probeV4)
        if (byName || v4 == "EPERM" || v4 == "EACCES") return v4
        return "v4 $v4, v6 ${one(probeV6)}"
    }

    private fun failure(e: Throwable): String {
        val errnoException = generateSequence(e) { it.cause }.take(5).filterIsInstance<ErrnoException>().firstOrNull()
        if (errnoException != null) {
            val name = try {
                OsConstants.errnoName(errnoException.errno)
            } catch (t: Throwable) {
                null
            }
            if (name != null) return name
        }
        return errnoIn(e.message)
    }

    private val errno = Regex("""\bE[A-Z]{3,}\b""")

    /** "ENETUNREACH" out of "connect failed: ENETUNREACH (Network is unreachable)"; else the words, cut. */
    internal fun errnoIn(message: String?): String =
        errno.find(message.orEmpty())?.value ?: message?.take(60)?.ifBlank { null } ?: "failed"
}
