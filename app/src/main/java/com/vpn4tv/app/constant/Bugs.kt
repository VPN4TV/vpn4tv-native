package com.vpn4tv.app.constant

import android.os.Build
import com.vpn4tv.app.BuildConfig

object Bugs {
    // TODO: remove launch after fixed
    // https://github.com/golang/go/issues/68760
    val fixAndroidStack =
        BuildConfig.DEBUG ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
}
