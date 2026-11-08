package com.vpn4tv.app.bg

import com.vpn4tv.app.bg.ServiceError.Reason
import com.vpn4tv.app.constant.Alert
import com.vpn4tv.app.converter.SubscriptionUpdater
import org.junit.Assert.assertEquals
import org.junit.Test

// Home tells what happened and what to do; the technical text stays for support.
class ServiceErrorTest {

    @Test
    fun noKeyOrAnEmptyOneIsAnEmptyKeyWhateverTheMessage() {
        assertEquals(Reason.EmptyKey, ServiceError.classify(Alert.EmptyConfiguration, null))
        assertEquals(Reason.EmptyKey, ServiceError.classify(Alert.EmptyConfiguration, "subscription returned no proxies"))
    }

    @Test
    fun theTunWithoutPermissionIsTheLostVpnPermission() {
        // VPNService.openTun's own words, as the core passes them on.
        val wrapped = "start service: start inbound/tun[tun-in]: configure tun interface: android: missing vpn permission"
        assertEquals(Reason.NoVpnPermission, ServiceError.classify(Alert.CreateService, wrapped))
        assertEquals(
            Reason.NoVpnPermission,
            ServiceError.classify(Alert.CreateService, "android: the application is not prepared or is revoked"),
        )
    }

    @Test
    fun aForegroundStartAndroidRefusesIsTheSystemsRefusal() {
        // IllegalStateException (Android 8+) and ForegroundServiceStartNotAllowedException (12+).
        assertEquals(
            Reason.SystemRefused,
            ServiceError.classify(
                Alert.StartService,
                "Not allowed to start service Intent { cmp=com.vpn4tv.hiddify/com.vpn4tv.app.bg.ProxyService }: app is in background",
            ),
        )
        assertEquals(
            Reason.SystemRefused,
            ServiceError.classify(Alert.StartService, "startForegroundService() not allowed due to mAllowStartForeground false"),
        )
    }

    @Test
    fun anythingElseIsAFailedStart() {
        assertEquals(Reason.StartFailed, ServiceError.classify(Alert.StartCommandServer, "Libbox init timeout"))
        assertEquals(Reason.StartFailed, ServiceError.classify(Alert.CreateService, "xray: bridge exited"))
        assertEquals(Reason.StartFailed, ServiceError.classify(Alert.CreateService, null))
        // "not allowed" means the system's refusal only for a start of the service.
        assertEquals(Reason.StartFailed, ServiceError.classify(Alert.CreateService, "operation not allowed"))
    }

    @Test
    fun aReasonFromTheCallerWinsAndTheTechnicalTextStaysAsBefore() {
        val error = ServiceError.of(Alert.CreateService, "subscription fetch failed: timeout", Reason.KeyDownloadFailed)
        assertEquals(Reason.KeyDownloadFailed, error.reason)
        assertEquals("CreateService: subscription fetch failed: timeout", error.detail)
        assertEquals("StartService: unknown", ServiceError.of(Alert.StartService, null).detail)
    }

    @Test
    fun aKeyWithoutServersIsNotAnInternetProblem() {
        val empty = ServiceError.keyUpdate(SubscriptionUpdater.EmptyKeyException())
        assertEquals(Reason.EmptyKey, empty.reason)
        val offline = ServiceError.keyUpdate(java.net.SocketTimeoutException("no answer from api in 5000 ms"))
        assertEquals(Reason.KeyUpdateFailed, offline.reason)
        assertEquals("no answer from api in 5000 ms", offline.detail)
        assertEquals("UnknownHostException", ServiceError.keyUpdate(java.net.UnknownHostException()).detail)
    }
}
