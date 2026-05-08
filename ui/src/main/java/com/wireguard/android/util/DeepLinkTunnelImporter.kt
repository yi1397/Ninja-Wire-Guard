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
import java.time.Instant
import java.time.format.DateTimeParseException

object DeepLinkTunnelImporter {
    data class Result(
        val tunnelName: String,
        val updatedExisting: Boolean,
        val shouldStart: Boolean,
        val autoDeleteAtMillis: Long?
    )

    suspend fun importFromIntent(intent: Intent): Result? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.scheme !in SUPPORTED_SCHEMES) return null

        val configText = resolveConfigText(uri)
            ?: throw IllegalArgumentException("Missing conf, config, conf_b64, config_b64, url, or fragment")
        val metadata = parseMetadata(configText)
        val configForParser = stripMetadata(configText)
        val config = withContext(Dispatchers.Default) {
            Config.parse(ByteArrayInputStream(configForParser.toByteArray(StandardCharsets.UTF_8)))
        }
        val name = resolveName(uri)
        val shouldStart = uri.booleanQueryParameter("up")
            ?: uri.booleanQueryParameter("start")
            ?: uri.booleanQueryParameter("activate")
            ?: metadata.activate
            ?: true
        val autoDeleteAtMillis = resolveAutoDeleteAtMillis(uri, metadata)
        val manager = Application.getTunnelManager()
        val tunnels = manager.getTunnels()
        val existing = tunnels[name]
        val tunnel = if (existing != null) {
            manager.setTunnelConfig(existing, config)
            existing
        } else {
            manager.create(name, config)
        }
        if (autoDeleteAtMillis != null)
            AutoDeleteTunnelScheduler.schedule(Application.get().applicationContext, tunnel.name, autoDeleteAtMillis)
        else
            AutoDeleteTunnelScheduler.cancel(Application.get().applicationContext, tunnel.name)
        return Result(tunnel.name, existing != null, shouldStart, autoDeleteAtMillis)
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

    private fun resolveAutoDeleteAtMillis(uri: Uri, metadata: Metadata): Long? {
        val now = System.currentTimeMillis()
        uri.getQueryParameter("delete_after")?.let { return now + parseDurationMillis(it) }
        uri.getQueryParameter("delete_in")?.let { return now + parseDurationMillis(it) }
        uri.getQueryParameter("ttl")?.let { return now + parseDurationMillis(it) }
        uri.getQueryParameter("delete_at")?.let { return parseTimestampMillis(it) }
        uri.getQueryParameter("expires_at")?.let { return parseTimestampMillis(it) }
        uri.getQueryParameter("expires")?.let { return parseTimestampMillis(it) }
        return metadata.deleteAtMillis
    }

    private fun parseMetadata(configText: String): Metadata {
        var activate: Boolean? = null
        var deleteAtMillis: Long? = null
        val now = System.currentTimeMillis()
        configText.lineSequence().forEach { line ->
            val match = METADATA_PATTERN.matchEntire(line) ?: return@forEach
            when (match.groupValues[1].lowercase()) {
                "activate", "up", "start" -> activate = match.groupValues[2].toBooleanOrNull()
                "delete-after", "auto-delete-after", "ttl" -> deleteAtMillis = now + parseDurationMillis(match.groupValues[2])
                "delete-at", "expires-at", "expires" -> deleteAtMillis = parseTimestampMillis(match.groupValues[2])
            }
        }
        return Metadata(activate, deleteAtMillis)
    }

    private fun stripMetadata(configText: String) =
        configText.lineSequence()
            .filterNot { METADATA_PATTERN.matches(it) }
            .joinToString("\n")

    private fun parseDurationMillis(rawValue: String): Long {
        val value = rawValue.trim()
        val match = DURATION_PATTERN.matchEntire(value)
            ?: throw IllegalArgumentException("Invalid duration: $rawValue")
        val amount = match.groupValues[1].toLong()
        val multiplier = when (match.groupValues[2].lowercase()) {
            "", "s", "sec", "secs", "second", "seconds" -> 1000L
            "m", "min", "mins", "minute", "minutes" -> 60_000L
            "h", "hr", "hrs", "hour", "hours" -> 3_600_000L
            "d", "day", "days" -> 86_400_000L
            else -> throw IllegalArgumentException("Invalid duration unit: $rawValue")
        }
        require(amount > 0) { "Duration must be positive" }
        return Math.multiplyExact(amount, multiplier)
    }

    private fun parseTimestampMillis(rawValue: String): Long {
        val value = rawValue.trim()
        value.toLongOrNull()?.let {
            return if (it < 10_000_000_000L) it * 1000 else it
        }
        try {
            return Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("Invalid timestamp: $rawValue")
        }
    }

    private fun Uri.booleanQueryParameter(name: String): Boolean? {
        return when (getQueryParameter(name)?.lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    private fun String.toBooleanOrNull() = when (trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }

    private data class Metadata(val activate: Boolean?, val deleteAtMillis: Long?)

    private val SUPPORTED_SCHEMES = setOf("ninjawg")
    private val METADATA_PATTERN = Regex("^\\s*[#;]\\s*NinjaWG-(Activate|Up|Start|Delete-After|Auto-Delete-After|TTL|Delete-At|Expires-At|Expires)\\s*:\\s*(.*?)\\s*$", RegexOption.IGNORE_CASE)
    private val DURATION_PATTERN = Regex("^(\\d+)\\s*([a-zA-Z]*)$")
    private const val DEFAULT_TUNNEL_NAME = "wg"
    private const val DOWNLOAD_TIMEOUT_MS = 10000
    private const val MAX_CONFIG_BYTES = 64 * 1024
}
