package io.github.romanvht.mtgandroid.utils

import java.security.SecureRandom

object SecretUtils {
    private val secureRandom = SecureRandom()

    /**
     * Generates fake-TLS MTProto secret format:
     *   ee + <16 random bytes hex> + <domain bytes hex>
     */
    fun generateFakeTlsSecret(domain: String): String {
        val cleanDomain = FormatUtils.cleanDomain(domain)
        val randomPart = ByteArray(16).also { secureRandom.nextBytes(it) }.toHex()
        val domainPart = cleanDomain.encodeToByteArray().toHex()
        return "ee$randomPart$domainPart"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
