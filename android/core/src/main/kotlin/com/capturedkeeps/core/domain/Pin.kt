package com.capturedkeeps.core.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Staff PINs are stored only as salted PBKDF2 hashes: "pbkdf2$iterations$salt$hash". */
object Pin {
    private const val ITERATIONS = 20_000
    private val b64 = Base64.getEncoder()

    fun validate(pin: String) = rule(pin.length in 4..6 && pin.all { it in '0'..'9' }) { "PIN must be 4 to 6 digits" }

    fun hash(pin: String): String {
        validate(pin)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "pbkdf2\$$ITERATIONS\$${b64.encodeToString(salt)}\$${b64.encodeToString(derive(pin, salt, ITERATIONS))}"
    }

    fun matches(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val dec = Base64.getDecoder()
        return MessageDigest.isEqual(derive(pin, dec.decode(parts[2]), parts[1].toInt()), dec.decode(parts[3]))
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded
}
