package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log

data class ContactInfo(
    val name: String,
    val phoneNumber: String
)

data class InstalledAppInfo(
    val name: String,
    val packageName: String
)

object DeviceActionHelper {

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
        }
    }

    fun findContactByName(context: Context, targetName: String): ContactInfo? {
        val cleanTarget = targetName.trim().lowercase()
        if (cleanTarget.isBlank()) return null

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )

            var bestMatch: ContactInfo? = null
            cursor?.let {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val name = if (nameIdx != -1) it.getString(nameIdx) else ""
                    val number = if (numberIdx != -1) it.getString(numberIdx) else ""

                    if (name.lowercase().contains(cleanTarget) || cleanTarget.contains(name.lowercase())) {
                        bestMatch = ContactInfo(name, number.replace(" ", "").replace("-", ""))
                        if (name.equals(cleanTarget, ignoreCase = true)) {
                            return bestMatch // Exact match
                        }
                    }
                }
            }
            return bestMatch
        } catch (e: Exception) {
            Log.e("DeviceActionHelper", "Error searching contact: ${e.message}")
            return null
        } finally {
            cursor?.close()
        }
    }

    fun launchAppByPackage(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("DeviceActionHelper", "Error launching app: $packageName", e)
            false
        }
    }

    fun openWhatsAppChat(context: Context, phoneNumber: String?, message: String): Boolean {
        return try {
            val cleanPhone = phoneNumber?.replace("+", "")?.replace(" ", "")?.replace("-", "")
            val url = if (!cleanPhone.isNullOrBlank()) {
                "https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}"
            } else {
                "https://api.whatsapp.com/send?text=${Uri.encode(message)}"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            try {
                // Fallback to general send intent
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(sendIntent)
                true
            } catch (e2: Exception) {
                Log.e("DeviceActionHelper", "WhatsApp open failed", e2)
                false
            }
        }
    }

    fun makePhoneCall(context: Context, phoneNumber: String): Boolean {
        return try {
            val cleanNumber = phoneNumber.trim()
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                context.startActivity(intent)
                true
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                true
            }
        } catch (e: Exception) {
            Log.e("DeviceActionHelper", "Phone call failed", e)
            false
        }
    }

    fun sendSmsDirect(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                true
            } else {
                val smsUri = Uri.parse("smsto:$phoneNumber")
                val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
        } catch (e: Exception) {
            Log.e("DeviceActionHelper", "SMS failed", e)
            false
        }
    }

    fun getInstalledMusicApps(context: Context): List<InstalledAppInfo> {
        val knownMusicPackages = mapOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.jio.media.jiobeats" to "JioSaavn",
            "com.bsbportal.music" to "Wynk Music",
            "com.gaana" to "Gaana",
            "com.apple.android.music" to "Apple Music",
            "com.amazon.mp3" to "Amazon Music"
        )
        val pm = context.packageManager
        val found = mutableListOf<InstalledAppInfo>()
        for ((pkg, name) in knownMusicPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                found.add(InstalledAppInfo(name, pkg))
            } catch (e: PackageManager.NameNotFoundException) {
                // Not installed
            }
        }
        return found
    }

    fun getInstalledNavigationApps(context: Context): List<InstalledAppInfo> {
        val knownMapPackages = mapOf(
            "com.google.android.apps.maps" to "Google Maps",
            "com.waze" to "Waze",
            "com.olacabs.customer" to "Ola",
            "com.ubercab" to "Uber"
        )
        val pm = context.packageManager
        val found = mutableListOf<InstalledAppInfo>()
        for ((pkg, name) in knownMapPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                found.add(InstalledAppInfo(name, pkg))
            } catch (e: PackageManager.NameNotFoundException) {
                // Not installed
            }
        }
        return found
    }

    fun openBrowserUrl(context: Context, url: String): Boolean {
        return try {
            val validUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else url
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
