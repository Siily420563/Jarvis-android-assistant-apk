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

    fun classify(query: String, currentPersona: PersonaType, assistantName: String = "SARA"): FastPathResult {
        val clean = query.trim().lowercase()

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

        // 3. Direct Simple Alarm FastPath (only if not a complex sentence)
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
