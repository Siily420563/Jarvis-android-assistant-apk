package com.example.brain

import com.example.actions.Action
import org.json.JSONArray
import org.json.JSONObject

data class AgentStep(
    val thought: String,
    val plan: List<String>,
    val action: Action,
    val expect: String,
    val rawJson: String = ""
) {
    companion object {
        fun fromJson(jsonStr: String): AgentStep? {
            return try {
                var clean = jsonStr.trim()
                if (clean.startsWith("```json")) clean = clean.removePrefix("```json").trim()
                if (clean.startsWith("```")) clean = clean.removePrefix("```").trim()
                if (clean.endsWith("```")) clean = clean.removeSuffix("```").trim()

                val obj = JSONObject(clean)
                val thought = obj.optString("thought", "")
                val planArray = obj.optJSONArray("plan") ?: JSONArray()
                val plan = mutableListOf<String>()
                for (i in 0 until planArray.length()) {
                    plan.add(planArray.optString(i))
                }
                val actionObj = obj.optJSONObject("action") ?: JSONObject().apply {
                    put("type", "Done")
                    put("summary", thought)
                }
                val action = Action.fromJson(actionObj)
                val expect = obj.optString("expect", "")
                AgentStep(thought, plan, action, expect, clean)
            } catch (e: Exception) {
                null
            }
        }
    }
}
