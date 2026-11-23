package com.capturedkeeps.data

import com.capturedkeeps.core.domain.RuleBroken
import com.capturedkeeps.core.domain.Staff
import java.time.Duration
import java.time.Instant

/** Wrong-PIN/password lockout, shared by payment confirmation and staff login so guesses add up across both. */
class Gate(private val now: () -> Instant = Instant::now) {
    private var wrong = 0
    private var lockedUntil = Instant.MIN

    suspend fun check(find: suspend () -> Staff?): Staff {
        val wait = Duration.between(now(), lockedUntil).seconds
        if (wait > 0) throw RuleBroken("Too many wrong tries. Try again in $wait s.")
        val staff = find()
        if (staff == null) {
            if (++wrong >= MAX_WRONG) { lockedUntil = now().plus(LOCK); wrong = 0 }
            throw RuleBroken("Wrong PIN or password")
        }
        wrong = 0
        return staff
    }

    companion object {
        const val MAX_WRONG = 5
        val LOCK: Duration = Duration.ofSeconds(60)
    }
}

/** [ownerTools] only when an owner used their password — a PIN alone never unlocks owner tools. */
data class Login(val staff: Staff, val ownerTools: Boolean)

suspend fun Gate.login(store: Store, secret: String): Login {
    var viaPassword = false
    val staff = check {
        store.ownerByPassword(secret)?.also { viaPassword = true }
            ?: secret.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { store.staffByPin(it) }
    }
    return Login(staff, viaPassword)
}
