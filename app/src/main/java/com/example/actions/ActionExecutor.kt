package com.example.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import com.example.alarm.JarvisAlarmScheduler
import com.example.data.db.JarvisAlarm
import com.example.data.db.JarvisDatabase
import com.example.data.db.UserMemory
import com.example.perception.ScreenState
import com.example.perception.UiElement
import com.example.safety.SafetyPolicy
import com.example.service.JarvisAccessibilityService
import com.example.util.DeviceActionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class ActionExecutor(
    private val context: Context,
    private val db: JarvisDatabase
) {

    suspend fun execute(action: Action, currentScreen: ScreenState): ActionResult {
        val service = JarvisAccessibilityService.instance

        return when (action) {
            is Action.Tap -> {
                if (service == null) return ActionResult(false, "Accessibility Service is offline")
                val target = currentScreen.elementLookup[action.elementId]
                val success = service.clickElementById(action.elementId)
                ActionResult(
                    success = success,
                    message = if (success) "Tapped element [${action.elementId}] '${target?.text?.ifBlank { target.contentDescription }}'"
                    else "Failed to tap element [${action.elementId}]",
                    elementBounds = target?.bounds
                )
            }

            is Action.TapAt -> {
                if (service == null) return ActionResult(false, "Accessibility Service is offline")
                val success = service.clickCoordinates(action.x, action.y)
                ActionResult(success, "Tapped coordinates (${action.x.toInt()}, ${action.y.toInt()})")
            }

            is Action.LongPress -> {
                if (service == null) return ActionResult(false, "Accessibility Service is offline")
                val target = currentScreen.elementLookup[action.elementId]
                val success = service.longPressElementById(action.elementId)
                ActionResult(success, "Long pressed element [${action.elementId}]", target?.bounds)
            }

            is Action.DoubleTap -> {
                if (service == null) return ActionResult(false, "Accessibility Service is offline")
                val target = currentScreen.elementLookup[action.elementId]
                val success = service.doubleTapElementById(action.elementId)
                ActionResult(success, "Double tapped element [${action.elementId}]", target?.bounds)
            }

            is Action.Type -> {
                if (service == null) return ActionResult(false, "Accessibility Service is offline")
                val target = currentScreen.elementLookup[action.elementId]
                val success = service.typeIntoElementById(action.elementId, action.text, action.submit)
                ActionResult(
                    success = success,
                    message = if (success) "Typed '${action.text}' into element [${action.elementId}]"
                    else "Failed to type into element [${action.elementId}]",
                    elementBounds = target?.bounds
                )
            }

            is Action.Scroll -> {
                if (service == null) return ActionResult(false, "Accessibility Service is offline")
                val success = when (action.direction.uppercase()) {
                    "UP" -> service.scrollUp()
                    "LEFT" -> service.swipeCoordinates(800f, 1000f, 200f, 1000f, 300)
                    "RIGHT" -> service.swipeCoordinates(200f, 1000f, 800f, 1000f, 300)
                    else -> service.scrollDown()
                }
                ActionResult(success, "Scrolled ${action.direction}")
            }

            is Action.Swipe -> {
                if (service == null) return ActionResult(false, "Accessibility Service is offline")
                val success = service.swipeCoordinates(action.startX, action.startY, action.endX, action.endY, action.durationMs)
                ActionResult(success, "Swiped from (${action.startX}, ${action.startY}) to (${action.endX}, ${action.endY})")
            }

            Action.Back -> {
                val ok = service?.performBack() ?: false
                ActionResult(ok, "Pressed Back")
            }

            Action.Home -> {
                val ok = service?.performHome() ?: false
                ActionResult(ok, "Navigated to Home screen")
            }

            Action.Recents -> {
                val ok = service?.performRecents() ?: false
                ActionResult(ok, "Opened Recent Apps")
            }

            Action.Notifications -> {
                val ok = service?.performNotifications() ?: false
                ActionResult(ok, "Opened Notification Panel")
            }

            Action.QuickSettings -> {
                val ok = service?.performQuickSettings() ?: false
                ActionResult(ok, "Opened Quick Settings")
            }

            Action.LockScreen -> {
                ActionResult(true, "Screen lock simulated")
            }

            is Action.OpenApp -> {
                val ok = DeviceActionHelper.launchAppByName(context, action.nameOrPackage)
                ActionResult(ok, if (ok) "Opened app: ${action.nameOrPackage}" else "Could not find app '${action.nameOrPackage}'")
            }

            is Action.OpenUrl -> {
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(true, "Opened URL: ${action.url}")
                } catch (e: Exception) {
                    ActionResult(false, "Failed to open URL: ${e.message}")
                }
            }

            is Action.OpenIntent -> {
                return try {
                    val intent = Intent(action.action).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        action.dataUri?.let { data = Uri.parse(it) }
                        action.packageName?.let { `package` = it }
                        action.extras.forEach { (k, v) -> putExtra(k, v) }
                    }
                    context.startActivity(intent)
                    ActionResult(true, "Launched intent: ${action.action}")
                } catch (e: Exception) {
                    ActionResult(false, "Failed to launch intent: ${e.message}")
                }
            }

            is Action.WaitFor -> {
                val targetText = action.textOrId.lowercase()
                var found = false
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < action.timeoutMs) {
                    delay(300)
                    val s = service?.captureScreenState() ?: currentScreen
                    if (s.elements.any { (it.text + " " + it.contentDescription).lowercase().contains(targetText) }) {
                        found = true
                        break
                    }
                }
                ActionResult(found, if (found) "Found '$targetText' on screen" else "Timed out waiting for '$targetText'")
            }

            is Action.WaitMs -> {
                delay(action.ms)
                ActionResult(true, "Waited ${action.ms}ms")
            }

            is Action.SystemToggle -> {
                val ok = when (action.toggle.uppercase()) {
                    "FLASHLIGHT", "TORCH" -> {
                        val on = action.state.uppercase() != "OFF"
                        DeviceActionHelper.setTorchMode(context, on)
                    }
                    "WIFI" -> DeviceActionHelper.openQuickSettingPanel(context, "WIFI")
                    "BLUETOOTH" -> DeviceActionHelper.openQuickSettingPanel(context, "BLUETOOTH")
                    "VOLUME" -> {
                        DeviceActionHelper.adjustVolume(context, if (action.state.contains("DOWN")) "DOWN" else "UP")
                    }
                    else -> DeviceActionHelper.openQuickSettingPanel(context, "SETTINGS")
                }
                ActionResult(ok, "Toggled ${action.toggle} (${action.state})")
            }

            is Action.MediaControl -> {
                val ok = DeviceActionHelper.controlMediaPlayback(context, action.command)
                ActionResult(ok, "Media control: ${action.command}")
            }

            is Action.SetAlarm -> {
                val id = db.jarvisDao().insertAlarm(
                    JarvisAlarm(hour = action.hour, minute = action.minute, label = action.label, isActive = true)
                ).toInt()
                JarvisAlarmScheduler.scheduleAlarm(context, action.hour, action.minute, action.label, id)
                ActionResult(true, "Alarm set for ${String.format("%02d:%02d", action.hour, action.minute)}")
            }

            is Action.SetTimer -> {
                val ok = try {
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                        putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                } catch (e: Exception) {
                    false
                }
                ActionResult(ok, "Timer set for ${action.seconds}s")
            }

            is Action.CreateCalendarEvent -> {
                val ok = try {
                    val intent = Intent(Intent.ACTION_INSERT)
                        .setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, action.title)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, action.startTimeMs)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, action.endTimeMs)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                } catch (e: Exception) {
                    false
                }
                ActionResult(ok, "Created calendar event: ${action.title}")
            }

            is Action.Call -> {
                val contact = DeviceActionHelper.findContactByName(context, action.contact)
                val number = contact?.phoneNumber ?: action.contact
                val ok = DeviceActionHelper.makePhoneCall(context, number)
                ActionResult(ok, "Calling ${contact?.name ?: action.contact}")
            }

            is Action.SendSms -> {
                val contact = DeviceActionHelper.findContactByName(context, action.contact)
                val number = contact?.phoneNumber ?: action.contact
                val ok = DeviceActionHelper.sendSmsDirect(context, number, action.text)
                ActionResult(ok, "Sent SMS to ${contact?.name ?: action.contact}")
            }

            Action.ReadScreen -> {
                val textNodes = currentScreen.elements.map { it.text }.filter { it.isNotBlank() }.take(8)
                val summary = textNodes.joinToString(". ")
                ActionResult(true, "Screen read: $summary")
            }

            Action.Screenshot -> {
                val ok = service?.performScreenshot() ?: false
                ActionResult(ok, "Screenshot captured")
            }

            is Action.Share -> {
                val ok = try {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, action.uriOrText)
                        action.target?.let { `package` = it }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (e: Exception) {
                    false
                }
                ActionResult(ok, "Sharing content")
            }

            is Action.Remember -> {
                db.jarvisDao().insertMemory(UserMemory(fact = "${action.key}: ${action.value}", category = "Learned"))
                ActionResult(true, "Saved preference: ${action.key}")
            }

            is Action.AskUser -> {
                ActionResult(true, "Asked user: ${action.question}")
            }

            is Action.Done -> {
                ActionResult(true, action.summary)
            }

            is Action.Fail -> {
                ActionResult(false, action.reason)
            }
        }
    }
}
