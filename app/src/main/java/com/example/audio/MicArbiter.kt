package com.example.audio

import android.util.Log

/**
 * Android only allows ONE active SpeechRecognizer session system-wide at a time.
 * The in-app mic (MainViewModel) and the background orb (JarvisFloatingBubbleService)
 * each create their own SpeechRecognizer with no idea the other exists - when both
 * are alive together (which is normal, since the orb runs in the background even
 * while the app is open) they fight over the same mic. That fight is the "on/off
 * loop" and "orb aur in-app button alag-alag kaam kar rahe" bug.
 *
 * This is a small coordination lock, not a full recognizer rewrite: whoever wants
 * to listen must acquire() first, and must release() when done (result, error, or
 * manual stop). If the mic is already held by the other side, acquire() returns
 * false and the caller should back off instead of starting a competing session.
 */
object MicArbiter {

    @Volatile
    private var owner: String? = null

    /** Call before starting a SpeechRecognizer session. Returns false if the other side already owns the mic. */
    @Synchronized
    fun acquire(requester: String): Boolean {
        if (owner != null && owner != requester) {
            Log.w("MicArbiter", "$requester wants the mic but '$owner' already holds it - backing off instead of double-starting")
            return false
        }
        owner = requester
        return true
    }

    /** Call on every result, error, and manual stop - not just the "happy path". */
    @Synchronized
    fun release(requester: String) {
        if (owner == requester) owner = null
    }

    @Synchronized
    fun isHeldByOther(requester: String): Boolean = owner != null && owner != requester
}
