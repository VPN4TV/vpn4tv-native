package com.vpn4tv.app.bg

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import com.vpn4tv.app.R
import com.vpn4tv.app.constant.Status
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.ui.MainActivity

class VpnToggleActivity : Activity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) BoxService.start()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        val profileCount = ProfileManager.profileCountBlocking()
        if (profileCount > 0) {
            when (BoxService.globalStatus.value) {
                Status.Started -> {
                    BoxService.stop()
                    finish()
                }
                else -> {
                    val intent = VpnService.prepare(this)
                    if (intent != null) {
                        vpnPermissionLauncher.launch(intent)
                    } else {
                        BoxService.start()
                        finish()
                    }
                }
            }
        } else {
            Toast.makeText(
                this,
                getString(R.string.widget_no_connections),
                Toast.LENGTH_SHORT,
            ).show()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            finish()
        }
        overridePendingTransition(0, 0)
    }
}
