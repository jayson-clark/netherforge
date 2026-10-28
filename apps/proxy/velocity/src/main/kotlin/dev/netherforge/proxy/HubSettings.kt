package dev.netherforge.proxy

import dev.netherforge.network.Auth
import dev.netherforge.network.NetworkLimits
import dev.netherforge.network.Protocol
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The hub's settings, `plugins/netherforge/hub.properties` on the proxy: where it listens, the secret every server
 * proves it knows, and the network's limits. Written with a new random secret the first time the proxy starts, so
 * a hub is never open without one. A value that isn't one (a negative number) is the default.
 */
data class HubSettings(val bind: String, val port: Int, val secret: String, val limits: NetworkLimits) {
    companion object {
        const val FILE = "hub.properties"

        fun read(file: Path, info: (String) -> Unit): HubSettings {
            if (!Files.exists(file)) {
                Files.createDirectories(file.parent)
                Files.writeString(file, template(Auth.newSecret()))
                info(
                    "NetherForge: wrote $file with a new secret; give it to each server as network.secret in its plugins/NetherForge/config.yml"
                )
            }
            val properties = Properties()
            Files.newBufferedReader(file).use(properties::load)
            fun text(key: String) = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            fun positive(key: String) = text(key)?.toLongOrNull()?.takeIf { it > 0 }
            val defaults = NetworkLimits()
            val secret = text("secret") ?: error("$file has no secret: set one (or delete the file for a new one)")
            require(secret.length >= MIN_SECRET) { "$file's secret is shorter than $MIN_SECRET characters" }
            return HubSettings(
                bind = text("bind") ?: "0.0.0.0",
                port = positive("port")?.toInt() ?: Protocol.DEFAULT_PORT,
                secret = secret,
                limits = NetworkLimits(
                    maxValueBytes = positive("max-value-bytes")?.toInt() ?: defaults.maxValueBytes,
                    maxKeysPerPackage = positive("max-keys-per-package")?.toInt() ?: defaults.maxKeysPerPackage,
                    maxBytesPerPackage = positive("max-bytes-per-package") ?: defaults.maxBytesPerPackage,
                    maxQueuedFrames = positive("max-queued-frames")?.toInt() ?: defaults.maxQueuedFrames,
                    requestTimeoutMillis = positive("request-timeout-seconds")?.let { it * 1000 } ?: defaults.requestTimeoutMillis
                )
            )
        }

        private const val MIN_SECRET = 16

        private fun template(secret: String): String {
            val limits = NetworkLimits()
            return """
                |# NetherForge's network hub. Every NetherForge server of the network connects here: in each server's
                |# plugins/NetherForge/config.yml, set network.enabled to true, network.hub to this proxy's address and
                |# the port below, network.server-name to the server's name in velocity.toml's [servers], and
                |# network.secret to the secret below. Keep the port off the internet: firewall it to your servers.
                |
                |# Where the hub listens.
                |bind=0.0.0.0
                |port=${Protocol.DEFAULT_PORT}
                |
                |# What every server proves it knows (never sent as it is). Anyone with it can join the network.
                |secret=$secret
                |
                |# The most one value may be as JSON: a message, a request or its reply, a map's value, a sent player's data.
                |max-value-bytes=${limits.maxValueBytes}
                |# What one package may keep in the network's shared maps, across all of them.
                |max-keys-per-package=${limits.maxKeysPerPackage}
                |max-bytes-per-package=${limits.maxBytesPerPackage}
                |# How long a request waits for the server asked before it fails.
                |request-timeout-seconds=${limits.requestTimeoutMillis / 1000}
                |# Frames waiting to be sent to one server; past it the server is too slow and is disconnected.
                |max-queued-frames=${limits.maxQueuedFrames}
                |
            """.trimMargin()
        }
    }
}
