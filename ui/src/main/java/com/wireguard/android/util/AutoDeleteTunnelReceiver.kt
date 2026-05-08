/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoDeleteTunnelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tunnelName = intent.getStringExtra(AutoDeleteTunnelScheduler.EXTRA_TUNNEL_NAME) ?: return
        AutoDeleteTunnelScheduler.deleteIfDue(context.applicationContext, tunnelName)
    }
}
