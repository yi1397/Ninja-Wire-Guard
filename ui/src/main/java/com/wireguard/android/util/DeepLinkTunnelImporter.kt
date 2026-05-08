/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object DeepLinkTunnelImporter {
    data class Result(val tunnelName: String, val updatedExisting: Boolean, val started: Boolean)

    suspend fun importFromIntent(intent: Intent): Result? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.scheme !in SUPPORTED_SCHEMES) return null

        val configText = resolveConfigText(uri)
            ?: throw IllegalArgumentException("Missing conf, config, conf_b64, config_b64, url, or fragment")
        val config = withContext(Dispatchers.Default) {
            Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))
        }
        val name = resolveName(uri)
        val shouldStart = uri.booleanQueryParameter("up") ||
            uri.booleanQueryParameter("start") ||
            uri.booleanQueryParameter("activate")
        val manager = Application.getTunnelManager()
        val tunnels = manager.getTunnels()
        val existing = tunnels[name]
        val tunnel = if (existing != null) {
            manager.setTunnelConfig(existing, config)
            existing
        } else {
            manager.create(name, config)
        }
        if (shouldStart)
            manager.setTunnelState(tunnel, Tunnel.State.UP)
        return Result(tunnel.name, existing != null, shouldStart)
    }

    private suspend fun resolveConfigText(uri: Uri): String? {
        uri.getQueryParameter("conf")?.takeIf { it.isNotBlank() }?.let { return it }
        uri.getQueryParameter("config")?.takeIf { it.isNotBlank() }?.let { return it }
        uri.getQueryParameter("conf_b64")?.takeIf { it.isNotBlank() }?.let { return decodeBase64(it) }
        uri.getQueryParameter("config_b64")?.takeIf { it.isNotBlank() }?.let { return decodeBase64(it) }
        uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }?.let { return downloadConfig(it) }
        return uri.fragment?.takeIf { it.contains("[Interface]") }
    }

    private fun resolveName(uri: Uri): String {
        val rawName = uri.getQueryParameter("name")
            ?: uri.getQueryParameter("tunnel")
            ?: uri.getQueryParameter("profile")
            ?: uri.lastPathSegment
            ?: DEFAULT_TUNNEL_NAME
        val withoutExtension = rawName.substringAfterLast('/').removeSuffix(".conf")
        val sanitized = withoutExtension
            .replace(Regex("[^A-Za-z0-9_=+.-]"), "_")
            .trim { it == '_' || it == '.' || it == '-' }
            .take(Tunnel.NAME_MAX_LENGTH)
        return sanitized.takeIf { it.isNotEmpty() && !Tunnel.isNameInvalid(it) } ?: DEFAULT_TUNNEL_NAME
    }

    private fun decodeBase64(text: String): String {
        val bytes = Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private suspend fun downloadConfig(url: String) = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
        connection.readTimeout = DOWNLOAD_TIMEOUT_MS
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299)
                throw IOException("HTTP $responseCode")
            String(connection.inputStream.readLimited(MAX_CONFIG_BYTES), StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes)
                    throw IOException("Config is too large")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun Uri.booleanQueryParameter(name: String): Boolean {
        return when (getQueryParameter(name)?.lowercase()) {
            "1", "true", "yes", "on" -> true
            else -> false
        }
    }

    private val SUPPORTED_SCHEMES = setOf("ninjawg")
    private const val DEFAULT_TUNNEL_NAME = "wg"
    private const val DOWNLOAD_TIMEOUT_MS = 10000
    private const val MAX_CONFIG_BYTES = 64 * 1024
}
