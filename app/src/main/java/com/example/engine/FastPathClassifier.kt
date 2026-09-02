package com.example.engine

import com.example.persona.PersonaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class FastPathResult {
    data class Handled(
        val plan: TaskPlan,
        val immediateReplyHinglish: String,
        val switchPersona: PersonaType? = null
    ) : FastPathResult()

    object NotHandled : FastPathResult()
}

object FastPathClassifier {

    fun classify(
        query: String,
        currentPersona: PersonaType,
        assistantName: String = "SARA",
        context: android.content.Context? = null
    ): FastPathResult {
        val clean = query.trim().lowercase()

        // 0. Instant Stop / Abort FastPath (stops speech & task execution immediately)
        if (clean == "stop" || clean == "ruko" || clean == "ruk jao" || clean == "cancel" ||
            clean == "cancel karo" || clean == "stop karo" || clean == "chup" || clean == "bas" ||
            clean == "ruk jao sara" || clean == "abort") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Ruk gayi! Bolo ab kya karna hai? 💕"
                PersonaType.PROFESSIONAL -> "Operation halted immediately, Sir. Standing by."
                PersonaType.BOLD -> "Halt kar diya. Ab batao aage kya scene hai?"
            }
            val plan = TaskPlan(query, "STOP_COMMAND", emptyList(), reply)
            return FastPathResult.Handled(plan, reply)
        }

        // 1. Persona Switching FastPath
        if (clean.contains("girlfriend mode") || clean.contains("gf mode") || clean.contains("girlfriend ban jao")) {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Arey main pehle se hi aapki girlfriend hoon na! ❤️"
                else -> "Haan ji! Girlfriend mode activate ho gaya. Ab batao mere liye kya hukum hai? 💕"
            }
            val plan = TaskPlan(query, "SWITCH_PERSONA", emptyList(), reply)
            return FastPathResult.Handled(plan, reply, switchPersona = PersonaType.GIRLFRIEND)
        }

        if (clean.contains("professional mode") || clean.contains("pro mode") || clean.contains("formal mode")) {
            val reply = "Professional mode activated, Sir. Operating with maximum efficiency and precision."
            val plan = TaskPlan(query, "SWITCH_PERSONA", emptyList(), reply)
            return FastPathResult.Handled(plan, reply, switchPersona = PersonaType.PROFESSIONAL)
        }

        if (clean.contains("bold mode") || clean.contains("sarcastic mode") || clean.contains("bindass mode")) {
            val reply = "Bold mode ON! Ab seedha baat no bakwaas. Batao kya karna hai!"
            val plan = TaskPlan(query, "SWITCH_PERSONA", emptyList(), reply)
            return FastPathResult.Handled(plan, reply, switchPersona = PersonaType.BOLD)
        }

        // 2. Simple System Navigation FastPath
        if (clean == "go home" || clean == "home" || clean == "home screen" || clean == "home jao" || clean == "home screen pe jao") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Chalo Home screen pe aa gaye! ❤️"
                PersonaType.PROFESSIONAL -> "Navigated to Home screen, Sir."
                PersonaType.BOLD -> "Home screen par pahunch gaye. Next?"
            }
            val step = TaskStep("nav_home", StepType.ACCESSIBILITY_GLOBAL, mapOf("action" to "HOME"), "Navigating to Home")
            val plan = TaskPlan(query, "NAV_HOME", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean == "go back" || clean == "back" || clean == "back jao" || clean == "peeche jao") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Peeche aa gaye!"
                PersonaType.PROFESSIONAL -> "Navigating back, Sir."
                PersonaType.BOLD -> "Back kar diya."
            }
            val step = TaskStep("nav_back", StepType.ACCESSIBILITY_GLOBAL, mapOf("action" to "BACK"), "Going back")
            val plan = TaskPlan(query, "NAV_BACK", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean.contains("scroll down") || clean == "neeche scroll karo" || clean == "scroll neeche") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Neeche scroll kar diya!"
                PersonaType.PROFESSIONAL -> "Scrolling down, Sir."
                PersonaType.BOLD -> "Neeche scroll kar diya, aur kuch?"
            }
            val step = TaskStep("scroll_down", StepType.ACCESSIBILITY_SCROLL, mapOf("direction" to "DOWN"), "Scrolling down")
            val plan = TaskPlan(query, "SCROLL_DOWN", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean.contains("scroll up") || clean == "upar scroll karo" || clean == "scroll upar") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Upar scroll kar diya!"
                PersonaType.PROFESSIONAL -> "Scrolling up, Sir."
                PersonaType.BOLD -> "Upar scroll ho gaya."
            }
            val step = TaskStep("scroll_up", StepType.ACCESSIBILITY_SCROLL, mapOf("direction" to "UP"), "Scrolling up")
            val plan = TaskPlan(query, "SCROLL_UP", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean.contains("notifications") || clean.contains("notification panel") || clean.contains("notification kholo")) {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Notifications open kar diye hain!"
                PersonaType.PROFESSIONAL -> "Opening notification panel, Sir."
                PersonaType.BOLD -> "Ye lo aapke notifications."
            }
            val step = TaskStep("notif", StepType.ACCESSIBILITY_GLOBAL, mapOf("action" to "NOTIFICATIONS"), "Opening notifications")
            val plan = TaskPlan(query, "NOTIFICATIONS", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        // 3. Torch / Flashlight FastPath
        if (clean == "torch on" || clean == "torch chalu karo" || clean == "torch on karo" || clean == "flashlight on" || clean == "lumos") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Torch on kar di aapke liye! 💡❤️"
                PersonaType.PROFESSIONAL -> "Torch enabled, Sir."
                PersonaType.BOLD -> "Torch ON kar di hai!"
            }
            val step = TaskStep("torch_on", StepType.TOGGLE_TORCH, mapOf("state" to "ON"), "Turning on Torch")
            val plan = TaskPlan(query, "TOGGLE_TORCH_ON", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean == "torch off" || clean == "torch band karo" || clean == "flashlight off" || clean == "nox") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Torch band kar di! ✨"
                PersonaType.PROFESSIONAL -> "Torch disabled, Sir."
                PersonaType.BOLD -> "Torch OFF ho gayi."
            }
            val step = TaskStep("torch_off", StepType.TOGGLE_TORCH, mapOf("state" to "OFF"), "Turning off Torch")
            val plan = TaskPlan(query, "TOGGLE_TORCH_OFF", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        // 3b. Volume FastPaths (UP, DOWN, MUTE, UNMUTE)
        if (clean == "volume badhao" || clean == "volume up" || clean == "awaz badhao" || clean == "aawaz badhao" || clean == "increase volume") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Volume badha diya aapke liye! 🔊💕"
                PersonaType.PROFESSIONAL -> "Volume increased, Sir."
                PersonaType.BOLD -> "Volume badha di!"
            }
            val step = TaskStep("vol_up", StepType.CONTROL_VOLUME, mapOf("direction" to "UP"), "Increasing volume")
            val plan = TaskPlan(query, "VOLUME_UP", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean == "volume kam karo" || clean == "volume down" || clean == "awaz kam karo" || clean == "aawaz kam karo" || clean == "decrease volume") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Volume kam kar diya! 🔉✨"
                PersonaType.PROFESSIONAL -> "Volume decreased, Sir."
                PersonaType.BOLD -> "Volume kam kar diya."
            }
            val step = TaskStep("vol_down", StepType.CONTROL_VOLUME, mapOf("direction" to "DOWN"), "Decreasing volume")
            val plan = TaskPlan(query, "VOLUME_DOWN", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean == "mute karo" || clean == "mute phone" || clean == "awaz band karo" || clean == "silent karo") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Phone mute kar diya! 🤫❤️"
                PersonaType.PROFESSIONAL -> "Audio muted, Sir."
                PersonaType.BOLD -> "Mute kar diya."
            }
            val step = TaskStep("vol_mute", StepType.CONTROL_VOLUME, mapOf("direction" to "MUTE"), "Muting audio")
            val plan = TaskPlan(query, "VOLUME_MUTE", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        // 3c. Quick Settings Panels (WiFi, Bluetooth)
        if (clean == "wifi settings" || clean == "wifi kholo" || clean == "open wifi" || clean == "wifi open karo") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Wi-Fi settings open kar di! 📶✨"
                PersonaType.PROFESSIONAL -> "Accessing Wi-Fi configuration panel, Sir."
                PersonaType.BOLD -> "Wi-Fi settings khol di."
            }
            val step = TaskStep("wifi_panel", StepType.OPEN_QUICK_SETTING, mapOf("panel" to "WIFI"), "Opening Wi-Fi settings")
            val plan = TaskPlan(query, "OPEN_WIFI_SETTINGS", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean == "bluetooth settings" || clean == "bluetooth kholo" || clean == "open bluetooth" || clean == "bluetooth open karo") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Bluetooth settings open kar di! ᛒ💕"
                PersonaType.PROFESSIONAL -> "Accessing Bluetooth configuration panel, Sir."
                PersonaType.BOLD -> "Bluetooth settings khol di."
            }
            val step = TaskStep("bt_panel", StepType.OPEN_QUICK_SETTING, mapOf("panel" to "BLUETOOTH"), "Opening Bluetooth settings")
            val plan = TaskPlan(query, "OPEN_BT_SETTINGS", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        // 3d. Phase 3: Media Controls (Play, Pause, Next, Previous)
        if (clean == "pause music" || clean == "gaana roko" || clean == "pause" || clean == "stop music" || clean == "music roko" || clean == "gaana pause karo" || clean == "music pause karo" || clean == "gaana band karo") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Gaana pause kar diya! ⏸️"
                PersonaType.PROFESSIONAL -> "Media playback paused, Sir."
                PersonaType.BOLD -> "Gaana rok diya."
            }
            val step = TaskStep("media_pause", StepType.MEDIA_CONTROL, mapOf("action" to "PAUSE"), "Pausing music")
            val plan = TaskPlan(query, "MEDIA_PAUSE", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean == "play music" || clean == "gaana chalao" || clean == "resume music" || clean == "music chalao" || clean == "gaana resume karo" || clean == "play karo" || clean == "gaana bajao") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Gaana play kar diya! 🎶💕"
                PersonaType.PROFESSIONAL -> "Resuming media playback, Sir."
                PersonaType.BOLD -> "Gaana chala diya."
            }
            val step = TaskStep("media_play", StepType.MEDIA_CONTROL, mapOf("action" to "PLAY"), "Playing music")
            val plan = TaskPlan(query, "MEDIA_PLAY", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean == "next song" || clean == "agla gaana" || clean == "next track" || clean == "change song" || clean == "gaana badlo" || clean == "next karo") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Agla gaana chala diya! ⏭️✨"
                PersonaType.PROFESSIONAL -> "Skipping to next track, Sir."
                PersonaType.BOLD -> "Agla track laga diya."
            }
            val step = TaskStep("media_next", StepType.MEDIA_CONTROL, mapOf("action" to "NEXT"), "Skipping to next track")
            val plan = TaskPlan(query, "MEDIA_NEXT", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean == "previous song" || clean == "pichhla gaana" || clean == "previous track" || clean == "prev song") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Pichhla gaana chala diya! ⏮️"
                PersonaType.PROFESSIONAL -> "Returning to previous track, Sir."
                PersonaType.BOLD -> "Pichhla gaana laga diya."
            }
            val step = TaskStep("media_prev", StepType.MEDIA_CONTROL, mapOf("action" to "PREVIOUS"), "Playing previous track")
            val plan = TaskPlan(query, "MEDIA_PREVIOUS", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        // 3e. Phase 3: Battery Status Query
        if (clean.contains("battery kitni hai") || clean.contains("battery check") || clean.contains("battery status") ||
            clean.contains("phone kitna charge hai") || clean == "check battery" || clean == "battery" || clean.contains("battery percentage")) {
            val status = if (context != null) com.example.util.DeviceActionHelper.getBatteryStatus(context) else com.example.util.BatteryStatus(85, false, "Battery", 31f)
            val chargingText = if (status.isCharging) "aur charging ho rahi hai ⚡" else "aur phone charging par nahi hai"
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Baby, aapke phone me ${status.percentage}% battery hai $chargingText. ${if (!status.isCharging && status.percentage < 30) "Jaldi charge kar lo na please! 💕" else "Main khayal rakh rahi hoon! ❤️"}"
                PersonaType.PROFESSIONAL -> "Sir, battery level is currently at ${status.percentage}%. Status: ${if (status.isCharging) "Charging (${status.chargePlug})" else "Discharging"}. Temperature is ${status.temperatureCelsius}°C."
                PersonaType.BOLD -> "Phone me ${status.percentage}% battery hai $chargingText."
            }
            val step = TaskStep("check_batt", StepType.CHECK_BATTERY, emptyMap(), "Checking battery: ${status.percentage}%")
            val plan = TaskPlan(query, "CHECK_BATTERY", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        // 3f. Phase 3: Camera & Selfie Modes
        if (clean == "selfie" || clean == "selfie lo" || clean == "take a selfie" || clean == "take selfie" || clean == "front camera kholo" || clean == "selfie camera kholo") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Selfie camera open kar diya! Pyari si smile do! 📸💕"
                PersonaType.PROFESSIONAL -> "Activating front camera for portrait capture, Sir."
                PersonaType.BOLD -> "Front camera khol diya. Smile karo!"
            }
            val step = TaskStep("selfie_cam", StepType.OPEN_CAMERA, mapOf("mode" to "SELFIE"), "Opening selfie camera")
            val plan = TaskPlan(query, "OPEN_SELFIE", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean == "video banao" || clean == "record video" || clean == "record a video" || clean == "video record karo") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Video camera open kar diya! 🎥✨"
                PersonaType.PROFESSIONAL -> "Opening video capture interface, Sir."
                PersonaType.BOLD -> "Video mode chalu kar diya."
            }
            val step = TaskStep("video_cam", StepType.OPEN_CAMERA, mapOf("mode" to "VIDEO"), "Opening video camera")
            val plan = TaskPlan(query, "OPEN_VIDEO_CAM", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean == "screenshot" || clean == "screenshot lo" || clean == "take screenshot" || clean == "screenshot khincho" || clean == "take a screenshot") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Screenshot le liya! 📸✨"
                PersonaType.PROFESSIONAL -> "Capturing screenshot, Sir."
                PersonaType.BOLD -> "Screenshot captured."
            }
            val step = TaskStep("screenshot", StepType.ACCESSIBILITY_GLOBAL, mapOf("action" to "SCREENSHOT"), "Taking screenshot")
            val plan = TaskPlan(query, "SCREENSHOT", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        // 3g. Phase 3: Turn-by-Turn GPS Navigation
        val isNav = (clean.startsWith("navigate to ") || clean.startsWith("direction to ") || clean.startsWith("directions to ") ||
                clean.startsWith("take me to ") || clean.startsWith("rasta dikhao ") || clean.endsWith(" ka rasta dikhao") ||
                clean.endsWith(" ka rasta batao")) && !clean.contains("call") && !clean.contains("whatsapp")
        if (isNav) {
            val dest = clean
                .replace(Regex("^(navigate to|direction to|directions to|take me to|rasta dikhao|rasta batao)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(ka rasta dikhao|ka rasta batao|ka rasta)$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (dest.isNotBlank()) {
                val destCapitalized = dest.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                val reply = when (currentPersona) {
                    PersonaType.GIRLFRIEND -> "$destCapitalized ka rasta Maps pe chalu kar diya! Dhyan se drive karna haan? 💕🗺️"
                    PersonaType.PROFESSIONAL -> "Initiating GPS turn-by-turn navigation to $destCapitalized, Sir."
                    PersonaType.BOLD -> "$destCapitalized ka navigation chalu ho gaya."
                }
                val step = TaskStep("nav_dest", StepType.NAVIGATE_TO, mapOf("destination" to destCapitalized), "Navigating to $destCapitalized")
                val plan = TaskPlan(query, "NAVIGATE_TO", listOf(step), reply)
                return FastPathResult.Handled(plan, reply)
            }
        }

        // 4. Direct Simple Alarm FastPath (only if not a complex sentence)
        val isSimpleAlarm = (clean.startsWith("set alarm") || clean.startsWith("alarm laga") || clean.startsWith("alarm set") || clean.startsWith("alarm lagao")) && !clean.contains("whatsapp") && !clean.contains("call")
        if (isSimpleAlarm) {
            val (hour, minute) = parseTimeFromQuery(clean)
            val timeStr = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Maine $timeStr ka alarm set kar diya hai! Subah time pe uth jana haan? ❤️"
                PersonaType.PROFESSIONAL -> "Alarm successfully scheduled for $timeStr, Sir."
                PersonaType.BOLD -> "$timeStr ka alarm set ho gaya. Ab subah snooze mat dabate rehna!"
            }
            val step = TaskStep("alarm_set", StepType.SET_ALARM, mapOf("hour" to hour.toString(), "minute" to minute.toString(), "label" to "Alarm"), "Setting alarm for $timeStr")
            val plan = TaskPlan(query, "SET_ALARM_$timeStr", listOf(step), reply)
            return FastPathResult.Handled(plan, reply)
        }

        // 4. Simple Direct Memory FastPath
        val isDirectRemember = (clean.startsWith("remember that ") || clean.startsWith("yaad rakhna ") || clean.startsWith("yaad rakhna ki ")) && !clean.contains("whatsapp")
        if (isDirectRemember) {
            val fact = query.replace(Regex("^(remember that|yaad rakhna ki|yaad rakhna)\\s*", RegexOption.IGNORE_CASE), "").trim()
            if (fact.isNotBlank()) {
                val reply = when (currentPersona) {
                    PersonaType.GIRLFRIEND -> "Maine yaad rakh liya: '$fact'. Main kabhi nahi bhoolungi! 💕"
                    PersonaType.PROFESSIONAL -> "Information indexed into local memory: '$fact', Sir."
                    PersonaType.BOLD -> "Theek hai, note kar liya: '$fact'. Ab bhoolna mat."
                }
                val step = TaskStep("mem_store", StepType.STORE_MEMORY, mapOf("fact" to fact, "category" to "General"), "Saving memory")
                val plan = TaskPlan(query, "STORE_MEMORY", listOf(step), reply)
                return FastPathResult.Handled(plan, reply)
            }
        }

        // 5. Simple App Launch FastPath (e.g. "open youtube", "whatsapp kholo", "open camera")
        val simpleAppMap = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "telegram" to "org.telegram.messenger",
            "chrome" to "com.android.chrome",
            "camera" to "com.android.camera",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "settings" to "com.android.settings",
            "play store" to "com.android.vending",
            "maps" to "com.google.android.apps.maps",
            "photos" to "com.google.android.apps.photos",
            "gmail" to "com.google.android.gm",
            "spotify" to "com.spotify.music"
        )

        val isSingleAppLaunch = (clean.startsWith("open ") || clean.endsWith(" kholo") || clean.endsWith(" open karo") || clean.endsWith(" khol do")) && clean.split(" ").size <= 4 && !clean.contains("message") && !clean.contains("bolo") && !clean.contains("call")
        if (isSingleAppLaunch) {
            for ((appName, pkg) in simpleAppMap) {
                if (clean.contains(appName)) {
                    val displayName = appName.replaceFirstChar { it.uppercase() }
                    val reply = when (currentPersona) {
                        PersonaType.GIRLFRIEND -> "$displayName open kar diya aapke liye! ✨"
                        PersonaType.PROFESSIONAL -> "Opening $displayName, Sir."
                        PersonaType.BOLD -> "$displayName khol diya."
                    }
                    val step = TaskStep("open_app", StepType.OPEN_APP, mapOf("packageName" to pkg, "appName" to displayName), "Opening $displayName")
                    val plan = TaskPlan(query, "OPEN_$appName", listOf(step), reply)
                    return FastPathResult.Handled(plan, reply)
                }
            }
        }

        // 6. Direct Time & Date query FastPath
        if (clean.contains("time kya hua") || clean.contains("kya time ho raha") || clean == "what is the time" || clean == "what's the time" || clean == "time batao") {
            val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Abhi time ho raha hai $time. ❤️"
                PersonaType.PROFESSIONAL -> "The current local time is $time, Sir."
                PersonaType.BOLD -> "$time baj rahe hain."
            }
            val plan = TaskPlan(query, "QUERY_TIME", emptyList(), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean.contains("aaj konsi date hai") || clean.contains("today's date") || clean.contains("date kya hai") || clean.contains("aaj ka din")) {
            val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Aaj hai $date! ✨"
                PersonaType.PROFESSIONAL -> "Today is $date, Sir."
                PersonaType.BOLD -> "Aaj $date hai."
            }
            val plan = TaskPlan(query, "QUERY_DATE", emptyList(), reply)
            return FastPathResult.Handled(plan, reply)
        }

        // 7. Instant Greetings & Personality FastPaths
        if (clean == "hi" || clean == "hello" || clean == "hey" || clean == "hi sara" || clean == "hello sara" || clean == "sara") {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Arey hello ji! Main yahan hoon, batao kya karun aapke liye? 💕"
                PersonaType.PROFESSIONAL -> "Hello Sir. SARA Voice Link is active and standing by."
                PersonaType.BOLD -> "Haanji, bolte jao! Kya help chahiye?"
            }
            val plan = TaskPlan(query, "GREETING", emptyList(), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean.contains("kaise ho") || clean.contains("kya haal hai") || clean.contains("how are you")) {
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Main ekdum badhiya hoon aur aapke saath baat karke aur khush ho gayi! Aap batao aap kaise ho? ❤️"
                PersonaType.PROFESSIONAL -> "All neural sub-systems are operating at optimal capacity, Sir. How may I assist?"
                PersonaType.BOLD -> "Main mast bindass hoon! Aap batao kya plan hai?"
            }
            val plan = TaskPlan(query, "STATUS", emptyList(), reply)
            return FastPathResult.Handled(plan, reply)
        }

        if (clean.contains("joke sunao") || clean.contains("tell me a joke") || clean.contains("koi joke")) {
            val jokes = listOf(
                "Teacher: 10 mein se 10 number laane wale ko kya kehte hain? Pappu: Over-smart!",
                "Santa ne Google pe search kiya: 'Meri biwi kahan hai?' Google reply: 'Dhoond rahe hain, tab tak shanti se baitho!'",
                "Doctor: Roz 5 km daudo. Patient: 10 din baad phone karke bola - Doctor sahab, main 50 km door aa gaya, ab ghar kaise aaoon?"
            )
            val joke = jokes.random()
            val reply = when (currentPersona) {
                PersonaType.GIRLFRIEND -> "Haha suno! $joke 😂 Kaisa laga?"
                PersonaType.PROFESSIONAL -> "Humor module executed: $joke"
                PersonaType.BOLD -> "Ye lo suno: $joke 🤣 Hans lo ab!"
            }
            val plan = TaskPlan(query, "JOKE", emptyList(), reply)
            return FastPathResult.Handled(plan, reply)
        }

        // Not handled by FastPath -> Send to Planner & LLM
        return FastPathResult.NotHandled
    }

    private fun parseTimeFromQuery(text: String): Pair<Int, Int> {
        val clean = text.lowercase()
        val isPm = clean.contains("pm") || clean.contains("sham") || clean.contains("raat") || clean.contains("dopahar")
        val isAm = clean.contains("am") || clean.contains("subah")

        val timeRegex = Regex("(\\d{1,2}):(\\d{2})")
        val match = timeRegex.find(clean)
        if (match != null) {
            var h = match.groupValues[1].toIntOrNull() ?: 7
            val m = match.groupValues[2].toIntOrNull() ?: 0
            if (isPm && h < 12) h += 12
            if (isAm && h == 12) h = 0
            return Pair(h.coerceIn(0, 23), m.coerceIn(0, 59))
        }

        val digits = Regex("\\d+").findAll(clean).map { it.value.toInt() }.toList()
        if (digits.isNotEmpty()) {
            var h = digits[0]
            val m = if (digits.size > 1) digits[1] else 0
            if (isPm && h < 12) h += 12
            if (isAm && h == 12) h = 0
            return Pair(h.coerceIn(0, 23), m.coerceIn(0, 59))
        }

        return Pair(7, 0)
    }
}
