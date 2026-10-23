package com.vpn4tv.app.bg

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vpn4tv.app.Application
import com.vpn4tv.app.database.ProfileManager
import com.vpn4tv.app.database.TypedProfile
import com.vpn4tv.app.converter.SubscriptionUpdater
import java.util.concurrent.TimeUnit

class UpdateProfileWork {
    companion object {
        private const val WORK_NAME = "UpdateProfile"
        private const val TAG = "UpdateProfileWork"

        suspend fun reconfigureUpdater() {
            runCatching {
                reconfigureUpdater0()
            }.onFailure {
                Log.e(TAG, "reconfigureUpdater", it)
            }
        }

        private suspend fun reconfigureUpdater0() {
            val remoteProfiles =
                ProfileManager.list()
                    .filter { it.typed.type == TypedProfile.Type.Remote && it.typed.autoUpdate }
            if (remoteProfiles.isEmpty()) {
                Log.i(TAG, "auto-update: no subscription profiles, cancelled")
                WorkManager.getInstance(Application.application).cancelUniqueWork(WORK_NAME)
                return
            }

            var minDelay =
                remoteProfiles.minByOrNull { it.typed.autoUpdateInterval }!!.typed.autoUpdateInterval.toLong()
            val nowSeconds = System.currentTimeMillis() / 1000L
            val minInitDelay =
                remoteProfiles.minOf { (it.typed.autoUpdateInterval * 60) - (nowSeconds - (it.typed.lastUpdated.time / 1000L)) }
            if (minDelay < 15) minDelay = 15
            // The initial delay only applies when the work is new; an existing
            // periodic work keeps its schedule (WorkManager UPDATE policy).
            Log.i(TAG, "auto-update: ${remoteProfiles.size} profile(s), every $minDelay min")
            WorkManager.getInstance(Application.application).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                    .apply {
                        if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                        // NetworkType.CONNECTED: TV boxes in standby drop
                        // connectivity; without the constraint JobScheduler
                        // kept dispatching the job into a frozen/starved
                        // process all night — vc50326's biggest ANR cluster
                        // (SystemJobService, 50 reports / 33 users). A
                        // subscription refresh is useless offline anyway.
                        setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                        // EXPONENTIAL 30m (was LINEAR 15m): fetch failures
                        // re-armed the job every 15 minutes, amplifying the
                        // standby dispatch storm. ExistingPeriodicWorkPolicy
                        // UPDATE applies this to the existing unique work on
                        // next app start — no migration needed.
                        setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    }
                    .build(),
            )
        }
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val remoteProfiles =
                ProfileManager.list()
                    .filter { it.typed.type == TypedProfile.Type.Remote && it.typed.autoUpdate }
            if (remoteProfiles.isEmpty()) return Result.success()
            var success = true
            for (profile in remoteProfiles) {
                val lastSeconds =
                    (System.currentTimeMillis() - profile.typed.lastUpdated.time) / 1000L
                if (lastSeconds < profile.typed.autoUpdateInterval * 60) {
                    continue
                }
                try {
                    // Same steps as the refresh buttons: fetch, parse, regenerate
                    // the config and its bridge sidecars. HwidService carries the
                    // DNS fallback, retries and the bell.a4e.ar mirror.
                    val outcome = SubscriptionUpdater.update(applicationContext, profile)
                    Log.i(TAG, "updated ${profile.name}: config=${outcome.configChanged} bridges=${outcome.bridgesChanged}")
                    // The reload re-syncs the bridges, so a changed link applies too.
                    SubscriptionUpdater.applyToRunningService(profile, outcome)
                } catch (e: Exception) {
                    Log.e(TAG, "update profile ${profile.name}", e)
                    success = false
                }
            }
            return if (success) {
                Result.success()
            } else {
                Result.retry()
            }
        }
    }
}
