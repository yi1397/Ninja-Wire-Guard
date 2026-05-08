/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wireguard.android.Application
import kotlinx.coroutines.launch

object AutoDeleteTunnelScheduler {
    const val EXTRA_TUNNEL_NAME = "tunnel_name"

    fun schedule(context: Context, tunnelName: String, expiresAtMillis: Long) {
        prefs(context).edit().putLong(tunnelName, expiresAtMillis).apply()
        scheduleAlarm(context, tunnelName, expiresAtMillis)
    }

    fun cancel(context: Context, tunnelName: String) {
        prefs(context).edit().remove(tunnelName).apply()
        alarmManager(context).cancel(pendingIntent(context, tunnelName))
    }

    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        prefs(context).all.forEach { (tunnelName, value) ->
            val expiresAtMillis = value as? Long ?: return@forEach
            if (expiresAtMillis <= now)
                deleteIfDue(context, tunnelName)
            else
                scheduleAlarm(context, tunnelName, expiresAtMillis)
        }
    }

    fun deleteIfDue(context: Context, tunnelName: String) {
        val expiresAtMillis = prefs(context).getLong(tunnelName, Long.MAX_VALUE)
        if (System.currentTimeMillis() < expiresAtMillis) return
        cancel(context, tunnelName)
        applicationScope.launch {
            try {
                val manager = Application.getTunnelManager()
                val tunnel = manager.getTunnels()[tunnelName] ?: return@launch
                manager.delete(tunnel)
                Log.i(TAG, "Auto-deleted tunnel $tunnelName")
            } catch (e: Throwable) {
                Log.e(TAG, "Unable to auto-delete tunnel $tunnelName", e)
            }
        }
    }

    private fun scheduleAlarm(context: Context, tunnelName: String, expiresAtMillis: Long) {
        alarmManager(context).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            expiresAtMillis,
            pendingIntent(context, tunnelName)
        )
    }

    private fun pendingIntent(context: Context, tunnelName: String): PendingIntent {
        val intent = Intent(context, AutoDeleteTunnelReceiver::class.java)
            .setPackage(context.packageName)
            .putExtra(EXTRA_TUNNEL_NAME, tunnelName)
        return PendingIntent.getBroadcast(
            context,
            tunnelName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "auto_delete_tunnels"
    private const val TAG = "WG/AutoDeleteTunnelScheduler"
}
