package com.example.engine

sealed class JarvisAction {
    data class RememberAction(val fact: String, val category: String) : JarvisAction()
    data class SetAlarmAction(val hour: Int, val minute: Int, val label: String) : JarvisAction()
    data class AccessibilityAction(val actionName: String, val text: String = "") : JarvisAction()
    data class OpenAppAction(val packageName: String, val url: String = "") : JarvisAction()
}

object ActionParser {
    fun parseActions(response: String): List<JarvisAction> {
        val actions = mutableListOf<JarvisAction>()
        val regex = Regex("<action\\s+type=\"([^\"]+)\"([^/>]*)/?>")
        val matches = regex.findAll(response)

        for (match in matches) {
            val type = match.groupValues[1]
            val attributesStr = match.groupValues[2]
            val attributes = parseAttributes(attributesStr)

            when (type.uppercase()) {
                "REMEMBER" -> {
                    val fact = attributes["fact"] ?: ""
                    val category = attributes["category"] ?: "General"
                    if (fact.isNotBlank()) {
                        actions.add(JarvisAction.RememberAction(fact, category))
                    }
                }
                "SET_ALARM" -> {
                    val hour = attributes["hour"]?.toIntOrNull() ?: 7
                    val minute = attributes["minute"]?.toIntOrNull() ?: 0
                    val label = attributes["label"] ?: "Alarm"
                    actions.add(JarvisAction.SetAlarmAction(hour, minute, label))
                }
                "ACCESSIBILITY" -> {
                    val actionName = attributes["actionName"] ?: "HOME"
                    val text = attributes["text"] ?: ""
                    actions.add(JarvisAction.AccessibilityAction(actionName, text))
                }
                "OPEN_APP" -> {
                    val packageName = attributes["packageName"] ?: ""
                    val url = attributes["url"] ?: ""
                    if (packageName.isNotBlank() || url.isNotBlank()) {
                        actions.add(JarvisAction.OpenAppAction(packageName, url))
                    }
                }
            }
        }
        return actions
    }

    private fun parseAttributes(attributesStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val attrRegex = Regex("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")
        attrRegex.findAll(attributesStr).forEach {
            map[it.groupValues[1]] = it.groupValues[2]
        }
        return map
    }

    fun stripActionTags(text: String): String {
        return text.replace(Regex("<action[^>]*/>"), "")
            .replace(Regex("<action[^>]*>.*?</action>"), "")
            .trim()
    }
}
