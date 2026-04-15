package io.github.romanvht.mtgandroid.utils

import android.util.Patterns
import java.net.InetAddress

object ValidationUtils {
    private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")

    fun isValidIpAddress(ip: String): Boolean {
        if (ip.isEmpty()) return false

        return try {
            InetAddress.getByName(ip)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isValidPort(port: String): Boolean {
        val portNum = port.toIntOrNull() ?: return false
        return portNum in 1..65535
    }

    fun isNonPrivilegedPort(port: String): Boolean {
        val portNum = port.toIntOrNull() ?: return false
        return portNum in 1024..65535
    }

    fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty()) return false

        return Patterns.DOMAIN_NAME.matcher(domain).matches() || Patterns.WEB_URL.matcher("http://$domain").matches()
    }

    fun isValidConcurrency(concurrency: String): Boolean {
        val value = concurrency.toIntOrNull() ?: return false
        return value in 1..65535
    }

    fun isValidTimeout(timeout: String): Boolean {
        val value = timeout.toIntOrNull() ?: return false
        return value in 1..30
    }

    fun isValidAntiReplayCache(cache: String): Boolean {
        val value = cache.toIntOrNull() ?: return false
        return value in 1..10
    }

    fun isValidMtgSecret(secret: String): Boolean {
        val trimmed = secret.trim()
        if (trimmed.length < 32 || trimmed.length % 2 != 0) return false
        return HEX_REGEX.matches(trimmed)
    }

    fun isValidWsSecret(secret: String): Boolean {
        val trimmed = secret.trim()
        if (!isValidMtgSecret(trimmed)) return false
        // ws mode prefers fake-tls format.
        return trimmed.startsWith("ee", ignoreCase = true) || trimmed.length == 32
    }

    fun isValidSecretForMode(secret: String, transportMode: String): Boolean {
        return if (transportMode == "websocket") {
            isValidWsSecret(secret)
        } else {
            isValidMtgSecret(secret)
        }
    }

    fun isValidProxySecret(secret: String): Boolean {
        return isValidMtgSecret(secret)
    }
    
}
