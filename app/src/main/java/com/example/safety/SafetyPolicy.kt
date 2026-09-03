package com.example.safety

import android.content.Context
import com.example.actions.Action
import com.example.data.prefs.PreferencesManager
import com.example.perception.UiElement

object SafetyPolicy {

    val SENSITIVE_PACKAGES = setOf(
        "com.phonepe.app",
        "com.google.android.apps.nbu.paisa.user",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "com.axis.mobile",
        "com.sbi.lotusintouch",
        "com.icicibank.mobile",
        "com.hdfcbank.android",
        "com.cred.android"
    )

    private val DANGEROUS_KEYWORDS = listOf(
        "delete", "remove", "erase", "uninstall", "factory reset",
        "send money", "transfer money", "pay ", "payment", "upi pin",
        "format disk", "clear data"
    )

    fun isProtectedPackage(packageName: String, userProtectedApps: Set<String> = emptySet()): Boolean {
        if (packageName.isBlank()) return false
        val lower = packageName.lowercase()
        return SENSITIVE_PACKAGES.any { lower.contains(it) } ||
                userProtectedApps.any { lower.contains(it.lowercase()) }
    }

    /**
     * Determines whether an Action requires explicit user confirmation before execution.
     */
    fun requiresConfirmation(
        action: Action,
        currentPackage: String,
        targetElement: UiElement? = null,
        userProtectedApps: Set<String> = emptySet()
    ): Pair<Boolean, String> {
        // 1. Check if the current app is sensitive/banking/protected
        if (isProtectedPackage(currentPackage, userProtectedApps)) {
            when (action) {
                is Action.Tap, is Action.TapAt, is Action.Type -> {
                    return Pair(true, "Action inside protected financial app ($currentPackage). Please confirm to proceed.")
                }
                else -> {}
            }
        }

        // 2. Check if typing into or interacting with password field
        if (targetElement?.isPassword == true) {
            return Pair(true, "Interacting with a password or PIN field requires your explicit confirmation.")
        }

        // 3. Inspect Action text or target text for irreversible/destructive keywords
        when (action) {
            is Action.Type -> {
                val lower = action.text.lowercase()
                if (DANGEROUS_KEYWORDS.any { lower.contains(it) }) {
                    return Pair(true, "Action involves sensitive input '${action.text}'. Confirm?")
                }
            }
            is Action.Tap -> {
                val elementText = (targetElement?.text + " " + targetElement?.contentDescription).lowercase()
                if (elementText.contains("delete") || elementText.contains("uninstall") ||
                    elementText.contains("pay") || elementText.contains("send money") ||
                    elementText.contains("transfer") || elementText.contains("erase")) {
                    val label = targetElement?.text?.ifBlank { "button" } ?: "button"
                    return Pair(true, "Tapping '$label' could be irreversible or perform a payment. Confirm?")
                }
            }
            is Action.OpenIntent -> {
                if (action.action.contains("DELETE", ignoreCase = true) || action.action.contains("UNINSTALL", ignoreCase = true)) {
                    return Pair(true, "Intent action '${action.action}' is destructive. Confirm?")
                }
            }
            else -> {}
        }

        return Pair(false, "")
    }

    /**
     * Hard blocks execution if action is strictly prohibited for security.
     */
    fun isHardBlocked(action: Action, targetElement: UiElement?): Boolean {
        // Never allow autonomous typing into masked password fields without direct user authorization
        if (action is Action.Type && targetElement?.isPassword == true && action.text.length > 2) {
            return true
        }
        return false
    }
}
