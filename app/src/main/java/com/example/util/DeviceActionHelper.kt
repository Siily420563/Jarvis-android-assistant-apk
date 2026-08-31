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
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
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

    private val commonPackageAliases = mapOf(
        "youtube" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "wa" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "camera" to "com.android.camera",
        "calculator" to "com.google.android.calculator",
        "calc" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock",
        "alarm" to "com.google.android.deskclock",
        "settings" to "com.android.settings",
        "setting" to "com.android.settings",
        "maps" to "com.google.android.apps.maps",
        "map" to "com.google.android.apps.maps",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "spotify" to "com.spotify.music",
        "music" to "com.spotify.music",
        "gmail" to "com.google.android.gm",
        "mail" to "com.google.android.gm",
        "telegram" to "org.telegram.messenger",
        "tg" to "org.telegram.messenger",
        "play store" to "com.android.vending",
        "playstore" to "com.android.vending",
        "store" to "com.android.vending",
        "contacts" to "com.google.android.contacts",
        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "files" to "com.google.android.apps.nbu.files",
        "file manager" to "com.google.android.apps.nbu.files",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "facebook" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "netflix" to "com.netflix.mediaclient",
        "amazon" to "in.amazon.mShop.android.shopping",
        "hotstar" to "in.startv.hotstar",
        "jio cinema" to "com.jio.media.ondemand",
        "jiocinema" to "com.jio.media.ondemand",
        "swiggy" to "in.swiggy.android",
        "zomato" to "com.application.zomato",
        "paytm" to "net.one97.paytm",
        "phonepe" to "com.phonepe.app",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "google pay" to "com.google.android.apps.nbu.paisa.user"
    )

    fun launchAppByName(context: Context, appName: String): Boolean {
        val cleanName = appName.trim().lowercase()
        if (cleanName.isBlank()) return false

        // 1. Direct package name match
        if (cleanName.contains(".") && launchAppByPackage(context, cleanName)) {
            return true
        }

        // 2. Exact or substring match from known aliases
        for ((alias, pkg) in commonPackageAliases) {
            if (cleanName == alias || cleanName.contains(alias) || alias.contains(cleanName)) {
                if (launchAppByPackage(context, pkg)) {
                    return true
                }
            }
        }

        // 3. Dynamic lookup through all installed launcher activities
        try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolvedList = pm.queryIntentActivities(mainIntent, 0)

            // Try exact label match first
            for (resolveInfo in resolvedList) {
                val label = resolveInfo.loadLabel(pm).toString().lowercase()
                val pkg = resolveInfo.activityInfo.packageName
                if (label == cleanName || label.contains(cleanName) || cleanName.contains(label)) {
                    val intent = pm.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        context.startActivity(intent)
                        return true
                    }
                }
            }

            // Try package name match
            for (resolveInfo in resolvedList) {
                val pkg = resolveInfo.activityInfo.packageName.lowercase()
                if (pkg.contains(cleanName)) {
                    val intent = pm.getLaunchIntentForPackage(resolveInfo.activityInfo.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        context.startActivity(intent)
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DeviceActionHelper", "Error searching launcher apps for '$appName'", e)
        }

        // 4. Fallback: Check standard Android Settings intents if requested
        if (cleanName.contains("setting")) {
            return try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) { false }
        }

        return false
    }

    fun openSettingsScreen(context: Context, settingType: String? = null): Boolean {
        return try {
            val action = when (settingType?.lowercase()) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS
                "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
                "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
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
