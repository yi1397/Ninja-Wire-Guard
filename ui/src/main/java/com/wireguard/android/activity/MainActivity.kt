/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBar
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.fragment.TunnelDetailFragment
import com.wireguard.android.fragment.TunnelEditorFragment
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.DeepLinkTunnelImporter
import com.wireguard.android.util.ErrorMessages
import kotlinx.coroutines.launch

/**
 * CRUD interface for WireGuard tunnels. This activity serves as the main entry point to the
 * WireGuard application, and contains several fragments for listing, viewing details of, and
 * editing the configuration and interface state of WireGuard tunnels.
 */
class MainActivity : BaseActivity(), FragmentManager.OnBackStackChangedListener {
    private var actionBar: ActionBar? = null
    private var isTwoPaneLayout = false
    private var backPressedCallback: OnBackPressedCallback? = null
    private var pendingDeepLinkTunnel: ObservableTunnel? = null
    private var pendingDeepLinkTunnelName: String? = null
    private val permissionActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val tunnel = pendingDeepLinkTunnel
        val tunnelName = pendingDeepLinkTunnelName
        pendingDeepLinkTunnel = null
        pendingDeepLinkTunnelName = null
        if (tunnel != null && tunnelName != null)
            lifecycleScope.launch { startDeepLinkTunnel(tunnel, tunnelName) }
    }

    private fun handleBackPressed() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        // If the two-pane layout does not have an editor open, going back should exit the app.
        if (isTwoPaneLayout && backStackEntries <= 1) {
            finish()
            return
        }

        if (backStackEntries >= 1)
            supportFragmentManager.popBackStack()

        // Deselect the current tunnel on navigating back from the detail pane to the one-pane list.
        if (backStackEntries == 1)
            selectedTunnel = null
    }

    override fun onBackStackChanged() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        backPressedCallback?.isEnabled = backStackEntries >= 1
        if (actionBar == null) return
        // Do not show the home menu when the two-pane layout is at the detail view (see above).
        val minBackStackEntries = if (isTwoPaneLayout) 2 else 1
        actionBar!!.setDisplayHomeAsUpEnabled(backStackEntries >= minBackStackEntries)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        actionBar = supportActionBar
        isTwoPaneLayout = findViewById<View?>(R.id.master_detail_wrapper) != null
        supportFragmentManager.addOnBackStackChangedListener(this)
        backPressedCallback = onBackPressedDispatcher.addCallback(this) { handleBackPressed() }
        onBackStackChanged()
        if (savedInstanceState == null)
            handleDeepLinkIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // The back arrow in the action bar should act the same as the back button.
                onBackPressedDispatcher.onBackPressed()
                true
            }

            R.id.menu_action_edit -> {
                supportFragmentManager.commit {
                    replace(if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container, TunnelEditorFragment())
                    setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    addToBackStack(null)
                }
                true
            }
            // This menu item is handled by the editor fragment.
            R.id.menu_action_save -> false
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSelectedTunnelChanged(
        oldTunnel: ObservableTunnel?,
        newTunnel: ObservableTunnel?
    ): Boolean {
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) {
            return false
        }

        val backStackEntries = fragmentManager.backStackEntryCount
        if (newTunnel == null) {
            // Clear everything off the back stack (all editors and detail fragments).
            fragmentManager.popBackStackImmediate(0, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            return true
        }
        if (backStackEntries == 2) {
            // Pop the editor off the back stack to reveal the detail fragment. Use the immediate
            // method to avoid the editor picking up the new tunnel while it is still visible.
            fragmentManager.popBackStackImmediate()
        } else if (backStackEntries == 0) {
            // Create and show a new detail fragment.
            fragmentManager.commit {
                add(if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container, TunnelDetailFragment())
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                addToBackStack(null)
            }
        }
        return true
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        if (intent == null) return
        lifecycleScope.launch {
            val result = try {
                DeepLinkTunnelImporter.importFromIntent(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Unable to import tunnel from deep link: ${DeepLinkTunnelImporter.describeIntent(intent)}", e)
                Toast.makeText(this@MainActivity, getString(R.string.import_error, ErrorMessages[e]), Toast.LENGTH_LONG).show()
                return@launch
            } ?: return@launch

            val tunnel = Application.getTunnelManager().getTunnels()[result.tunnelName]
            selectedTunnel = tunnel
            if (result.shouldStart && tunnel != null) {
                startDeepLinkTunnelWithPermission(tunnel, result.tunnelName)
                return@launch
            }
            val message = getString(
                when {
                    result.updatedExisting -> R.string.deeplink_import_update_success
                    else -> R.string.deeplink_import_success
                },
                result.tunnelName
            )
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun startDeepLinkTunnelWithPermission(tunnel: ObservableTunnel, tunnelName: String) {
        if (Application.getBackend() is GoBackend) {
            try {
                val intent = GoBackend.VpnService.prepare(this)
                if (intent != null) {
                    pendingDeepLinkTunnel = tunnel
                    pendingDeepLinkTunnelName = tunnelName
                    permissionActivityResultLauncher.launch(intent)
                    return
                }
            } catch (e: Throwable) {
                val message = getString(R.string.error_prepare, ErrorMessages[e])
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                Log.e(TAG, message, e)
                return
            }
        }
        startDeepLinkTunnel(tunnel, tunnelName)
    }

    private suspend fun startDeepLinkTunnel(tunnel: ObservableTunnel, tunnelName: String) {
        try {
            Application.getTunnelManager().setTunnelState(tunnel, Tunnel.State.UP)
            Toast.makeText(this, getString(R.string.deeplink_import_and_start_success, tunnelName), Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            val message = getString(R.string.error_up, ErrorMessages[e])
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            Log.e(TAG, message, e)
        }
    }

    companion object {
        private const val TAG = "WG/MainActivity"
    }
}
