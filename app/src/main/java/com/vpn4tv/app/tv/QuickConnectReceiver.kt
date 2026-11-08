package com.vpn4tv.app.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * The TV launcher's events for the "Quick connect" channel: right after the app
 * is installed it asks for the default channel (there is one only once a key
 * exists, restored from a backup for instance), and a card the user removes
 * from the home screen is deleted and never put back.
 */
class QuickConnectReceiver : BroadcastReceiver() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TvContractCompat.ACTION_INITIALIZE_PROGRAMS -> QuickConnectChannel.requestSync(context)
            TvContractCompat.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED -> {
                val programId = intent.getLongExtra(TvContractCompat.EXTRA_PREVIEW_PROGRAM_ID, -1L)
                val pending = goAsync()
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        QuickConnectChannel.programRemoved(context.applicationContext, programId)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
