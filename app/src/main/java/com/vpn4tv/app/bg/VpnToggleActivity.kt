package com.vpn4tv.app.bg

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.vpn4tv.app.R
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.Settings
import com.vpn4tv.app.ui.MainActivity
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VpnToggleActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) BoxService.start()
        finish()
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        GlobalScope.launch(Dispatchers.IO) {
            if (!VpnConnectHelper.ensureSelectedProfileBlocking()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@VpnToggleActivity,
                        getString(R.string.widget_no_connections),
                        Toast.LENGTH_SHORT,
                    ).show()
                    startActivity(
                        Intent(this@VpnToggleActivity, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                    finish()
                }
                return@launch
            }

            when (BoxService.globalStatus.value) {
                Status.Started -> {
                    BoxService.stop()
                    withContext(Dispatchers.Main) { finish() }
                }
                else -> withContext(Dispatchers.Main) { startVpn() }
            }
        }
    }

    private fun startVpn() {
        if (Settings.isProxyMode) {
            BoxService.start()
            finish()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            BoxService.start()
            finish()
        }
    }
}
