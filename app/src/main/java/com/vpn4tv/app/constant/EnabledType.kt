package com.vpn4tv.app.constant

import android.content.Context
import com.vpn4tv.app.R

enum class EnabledType(val boolValue: Boolean) {
    Enabled(true),
    Disabled(false),
    ;

    fun getString(context: Context): String = when (this) {
        Enabled -> context.getString(R.string.enabled)
        Disabled -> context.getString(R.string.disabled)
    }

    companion object {
        fun from(value: Boolean): EnabledType = if (value) Enabled else Disabled

        fun valueOf(context: Context, value: String): EnabledType = when (value) {
            context.getString(R.string.enabled) -> Enabled
            context.getString(R.string.disabled) -> Disabled
            else -> Disabled
        }
    }
}
