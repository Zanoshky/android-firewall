package com.zanoshky.firewall

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Optional PIN gate for the app UI.
 *
 * State ownership:
 *  - The PIN salt/hash live in their own SharedPreferences file ("lock_prefs").
 *    Nothing else writes to it, and [BackupManager] deliberately never reads or
 *    writes it, so a shared backup file can never leak or overwrite someone's PIN.
 *  - Whether the current process is unlocked is in-memory only. Process death
 *    always relocks. There is no "remember me" persisted anywhere.
 *
 * Relock timing is tied to the activity lifecycle rather than a wall-clock
 * timeout: [onActivityStopped] relocks unless the app itself declared that it
 * was about to launch an external activity via [suppressNextRelock] (VPN consent
 * dialog, file picker, share sheet, self-recreate). That keeps relocking
 * deterministic instead of depending on how long the user spends in a picker.
 *
 * Scope: this is a tamper deterrent against someone holding the unlocked phone,
 * not a security boundary. It cannot stop uninstalling the app or killing the
 * VPN from Android's own system settings.
 */
object AppLock {

    private const val PREFS_NAME = "lock_prefs"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"

    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 12

    /** Failed unlock attempts allowed before a cool-down kicks in. */
    private const val MAX_ATTEMPTS = 5
    private const val LOCKOUT_MS = 30_000L

    @Volatile private var unlocked = false
    @Volatile private var suppressRelock = false
    @Volatile private var failedAttempts = 0
    @Volatile private var lockedOutUntil = 0L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when a PIN has been set. */
    fun isEnabled(context: Context): Boolean =
        !prefs(context).getString(KEY_HASH, null).isNullOrEmpty()

    /** True when the UI must be covered by the lock screen right now. */
    fun needsUnlock(context: Context): Boolean = isEnabled(context) && !unlocked

    /**
     * Declare that the app is about to start an activity that will background it
     * on purpose. The next [onActivityStopped] will not relock.
     */
    fun suppressNextRelock() {
        suppressRelock = true
    }

    fun onActivityStopped() {
        if (suppressRelock) {
            suppressRelock = false
        } else {
            unlocked = false
        }
    }

    // --- Cool-down after repeated failures ---

    /** Remaining cool-down in milliseconds, or 0 when unlock attempts are allowed. */
    fun lockoutRemainingMs(): Long {
        val remaining = lockedOutUntil - SystemClock.elapsedRealtime()
        return if (remaining > 0) remaining else 0
    }

    fun attemptsRemaining(): Int = (MAX_ATTEMPTS - failedAttempts).coerceAtLeast(0)

    /**
     * Verify [pin] and unlock the process on success. Runs PBKDF2, so call this
     * from a background dispatcher.
     */
    fun verify(context: Context, pin: String): Boolean {
        if (lockoutRemainingMs() > 0) return false

        val p = prefs(context)
        val storedHash = p.getString(KEY_HASH, null) ?: return false
        val storedSalt = p.getString(KEY_SALT, null) ?: return false

        val candidate = derive(pin, decodeHex(storedSalt))
        val matches = MessageDigest.isEqual(candidate, decodeHex(storedHash))

        if (matches) {
            failedAttempts = 0
            lockedOutUntil = 0
            unlocked = true
        } else {
            failedAttempts++
            if (failedAttempts >= MAX_ATTEMPTS) {
                lockedOutUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS
                failedAttempts = 0
            }
        }
        return matches
    }

    /**
     * Store a new PIN and treat the current process as unlocked. Runs PBKDF2, so
     * call this from a background dispatcher.
     */
    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString(KEY_SALT, encodeHex(salt))
            .putString(KEY_HASH, encodeHex(derive(pin, salt)))
            .apply()
        failedAttempts = 0
        lockedOutUntil = 0
        unlocked = true
    }

    fun clearPin(context: Context) {
        prefs(context).edit().remove(KEY_HASH).remove(KEY_SALT).apply()
        failedAttempts = 0
        lockedOutUntil = 0
        unlocked = true
    }

    fun validatePinFormat(pin: String): String? = when {
        pin.length < MIN_PIN_LENGTH -> "PIN must be at least $MIN_PIN_LENGTH digits"
        pin.length > MAX_PIN_LENGTH -> "PIN must be at most $MAX_PIN_LENGTH digits"
        !pin.all { it.isDigit() } -> "PIN must contain digits only"
        else -> null
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encodeHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun decodeHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
