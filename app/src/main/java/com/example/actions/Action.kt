package com.example.actions

import org.json.JSONObject

sealed class Action {
    data class Tap(val elementId: Int) : Action()
    data class TapAt(val x: Float, val y: Float) : Action()
    data class LongPress(val elementId: Int) : Action()
    data class DoubleTap(val elementId: Int) : Action()
    data class Type(val elementId: Int, val text: String, val submit: Boolean = false) : Action()
    data class Scroll(val direction: String = "DOWN", val elementId: Int? = null) : Action()
    data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float, val durationMs: Long = 300) : Action()
    object Back : Action()
    object Home : Action()
    object Recents : Action()
    object Notifications : Action()
    object QuickSettings : Action()
    object LockScreen : Action()
    data class OpenApp(val nameOrPackage: String) : Action()
    data class OpenUrl(val url: String) : Action()
    data class OpenIntent(
        val action: String,
        val dataUri: String? = null,
        val packageName: String? = null,
        val extras: Map<String, String> = emptyMap()
    ) : Action()
    data class WaitFor(val textOrId: String, val timeoutMs: Long = 3000) : Action()
    data class WaitMs(val ms: Long) : Action()
    data class SystemToggle(val toggle: String, val state: String = "TOGGLE") : Action()
    data class MediaControl(val command: String) : Action()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String = "Alarm") : Action()
    data class SetTimer(val seconds: Int, val label: String = "Timer") : Action()
    data class CreateCalendarEvent(val title: String, val startTimeMs: Long, val endTimeMs: Long) : Action()
    data class Call(val contact: String) : Action()
    data class SendSms(val contact: String, val text: String) : Action()
    object ReadScreen : Action()
    object Screenshot : Action()
    data class Share(val uriOrText: String, val target: String? = null) : Action()
    data class Remember(val key: String, val value: String) : Action()
    data class AskUser(val question: String) : Action()
    data class Done(val summary: String) : Action()
    data class Fail(val reason: String) : Action()

    companion object {
        fun fromJson(json: JSONObject): Action {
            val type = json.optString("type", json.optString("name", "")).trim()
            val params = json.optJSONObject("params") ?: json

            return when (type.lowercase()) {
                "tap", "click" -> {
                    val id = params.optInt("elementId", params.optInt("id", -1))
                    if (id > 0) Tap(id)
                    else {
                        val x = params.optDouble("x", -1.0).toFloat()
                        val y = params.optDouble("y", -1.0).toFloat()
                        if (x >= 0 && y >= 0) TapAt(x, y) else Fail("Missing tap target")
                    }
                }
                "tapat", "clickat", "tap_at" -> {
                    val x = params.optDouble("x", 500.0).toFloat()
                    val y = params.optDouble("y", 1000.0).toFloat()
                    TapAt(x, y)
                }
                "longpress", "long_press" -> {
                    val id = params.optInt("elementId", params.optInt("id", 1))
                    LongPress(id)
                }
                "doubletap", "double_tap" -> {
                    val id = params.optInt("elementId", params.optInt("id", 1))
                    DoubleTap(id)
                }
                "type", "input", "inputtext", "type_text" -> {
                    val id = params.optInt("elementId", params.optInt("id", -1))
                    val text = params.optString("text", params.optString("value", ""))
                    val submit = params.optBoolean("submit", false)
                    Type(id, text, submit)
                }
                "scroll" -> {
                    val dir = params.optString("direction", "DOWN").uppercase()
                    val id = if (params.has("elementId")) params.optInt("elementId") else null
                    Scroll(dir, id)
                }
                "swipe" -> {
                    val sx = params.optDouble("startX", 500.0).toFloat()
                    val sy = params.optDouble("startY", 1200.0).toFloat()
                    val ex = params.optDouble("endX", 500.0).toFloat()
                    val ey = params.optDouble("endY", 400.0).toFloat()
                    val dur = params.optLong("durationMs", 300)
                    Swipe(sx, sy, ex, ey, dur)
                }
                "back", "goback" -> Back
                "home", "gohome" -> Home
                "recents", "appswitch" -> Recents
                "notifications", "notification_panel" -> Notifications
                "quicksettings", "quick_settings" -> QuickSettings
                "lockscreen", "lock_screen" -> LockScreen
                "openapp", "open_app", "launch" -> {
                    val name = params.optString("nameOrPackage", params.optString("appName", params.optString("name", params.optString("package", ""))))
                    OpenApp(name)
                }
                "openurl", "open_url" -> {
                    val url = params.optString("url", "")
                    OpenUrl(url)
                }
                "openintent", "open_intent" -> {
                    val act = params.optString("action", "android.intent.action.VIEW")
                    val uri = params.optString("dataUri", null)
                    val pkg = params.optString("packageName", null)
                    OpenIntent(act, uri, pkg)
                }
                "waitfor", "wait_for" -> {
                    val target = params.optString("textOrId", params.optString("text", ""))
                    val timeout = params.optLong("timeoutMs", 3000)
                    WaitFor(target, timeout)
                }
                "waitms", "wait", "sleep" -> {
                    val ms = params.optLong("ms", params.optLong("durationMs", 1000))
                    WaitMs(ms)
                }
                "systemtoggle", "system_toggle", "toggle" -> {
                    val toggle = params.optString("toggle", params.optString("setting", "FLASHLIGHT"))
                    val state = params.optString("state", "TOGGLE")
                    SystemToggle(toggle, state)
                }
                "mediacontrol", "media_control", "media" -> {
                    val cmd = params.optString("command", params.optString("action", "PLAY_PAUSE"))
                    MediaControl(cmd)
                }
                "setalarm", "set_alarm" -> {
                    val h = params.optInt("hour", 7)
                    val m = params.optInt("minute", 0)
                    val l = params.optString("label", "Alarm")
                    SetAlarm(h, m, l)
                }
                "settimer", "set_timer" -> {
                    val s = params.optInt("seconds", 60)
                    val l = params.optString("label", "Timer")
                    SetTimer(s, l)
                }
                "createcalendarevent", "calendar_event" -> {
                    val t = params.optString("title", "Meeting")
                    val s = params.optLong("startTimeMs", System.currentTimeMillis() + 3600_000)
                    val e = params.optLong("endTimeMs", s + 3600_000)
                    CreateCalendarEvent(t, s, e)
                }
                "call" -> {
                    val contact = params.optString("contact", params.optString("name", params.optString("number", "")))
                    Call(contact)
                }
                "sendsms", "send_sms", "sms" -> {
                    val contact = params.optString("contact", params.optString("name", ""))
                    val text = params.optString("text", params.optString("message", ""))
                    SendSms(contact, text)
                }
                "readscreen", "read_screen" -> ReadScreen
                "screenshot" -> Screenshot
                "share" -> {
                    val item = params.optString("uriOrText", params.optString("text", ""))
                    val target = params.optString("target", null)
                    Share(item, target)
                }
                "remember", "store_memory" -> {
                    val k = params.optString("key", params.optString("fact", ""))
                    val v = params.optString("value", "")
                    Remember(k, v)
                }
                "askuser", "ask_user", "ask" -> {
                    val q = params.optString("question", params.optString("prompt", ""))
                    AskUser(q)
                }
                "done", "complete" -> {
                    val summary = params.optString("summary", params.optString("message", "Task completed successfully."))
                    Done(summary)
                }
                "fail", "error" -> {
                    val reason = params.optString("reason", params.optString("message", "Unable to complete task."))
                    Fail(reason)
                }
                else -> Fail("Unknown action type: $type")
            }
        }
    }
}

data class ActionResult(
    val success: Boolean,
    val message: String,
    val elementBounds: android.graphics.Rect? = null
)
