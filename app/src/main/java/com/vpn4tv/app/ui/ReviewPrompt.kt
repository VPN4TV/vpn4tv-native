package com.vpn4tv.app.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import com.vpn4tv.app.bg.UsageMilestones
import com.vpn4tv.app.database.Settings

/**
 * Google Play's own rating card (In-App Review), asked for after a connect
 * once the VPN has worked on five different days without a failed start in
 * between, and not again for half a year. Google's rules: no question
 * before it ("do you like the app?"), no button for it, no reward, the card
 * as Play draws it; Play also limits how often it shows. Phones and tablets,
 * and TVs with Google TV (the TVs the API supports). Without Google Play the
 * request fails and nothing shows.
 */
object ReviewPrompt {
    private const val TAG = "ReviewPrompt"

    /** Google's system feature of a Google TV device (Android TV's Google TV guide). */
    private const val GOOGLE_TV_FEATURE = "com.google.android.feature.AMATI_EXPERIENCE"

    fun due(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (Device.isTv && !context.packageManager.hasSystemFeature(GOOGLE_TV_FEATURE)) return false
        return UsageMilestones.reviewDue(UsageMilestones.load(), Settings.reviewAskedAt, now)
    }

    /**
     * Counted as asked before Play answers: a failure here waits for the next
     * interval too. No Google Play, an install Play does not know, no
     * network: the task fails and the user is told nothing (Google's advice).
     */
    fun launch(activity: Activity) {
        Settings.reviewAskedAt = System.currentTimeMillis()
        UsageMilestones.save(UsageMilestones.asked(UsageMilestones.load()))
        try {
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { request ->
                if (!request.isSuccessful) {
                    Log.i(TAG, "review flow not available: ${request.exception?.message}")
                    return@addOnCompleteListener
                }
                if (activity.isFinishing || activity.isDestroyed) return@addOnCompleteListener
                try {
                    // Play does not say whether the card showed or what was rated.
                    manager.launchReviewFlow(activity, request.result).addOnCompleteListener {
                        Log.i(TAG, "review flow finished")
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "review flow not shown: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "review flow not available: ${e.message}")
        }
    }
}
