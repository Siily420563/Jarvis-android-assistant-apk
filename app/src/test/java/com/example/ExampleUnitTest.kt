package com.example

import com.example.engine.FastPathClassifier
import com.example.engine.FastPathResult
import com.example.engine.StepType
import com.example.engine.TaskPlan
import com.example.engine.TaskStep
import com.example.persona.PersonaType
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testPersonaSwitchingFastPath() {
        val gfResult = FastPathClassifier.classify("girlfriend mode activate karo", PersonaType.PROFESSIONAL)
        assertTrue(gfResult is FastPathResult.Handled)
        val handled = gfResult as FastPathResult.Handled
        assertEquals(PersonaType.GIRLFRIEND, handled.switchPersona)

        val proResult = FastPathClassifier.classify("switch to professional mode", PersonaType.GIRLFRIEND)
        assertTrue(proResult is FastPathResult.Handled)
        assertEquals(PersonaType.PROFESSIONAL, (proResult as FastPathResult.Handled).switchPersona)

        val boldResult = FastPathClassifier.classify("bold mode on karo", PersonaType.PROFESSIONAL)
        assertTrue(boldResult is FastPathResult.Handled)
        assertEquals(PersonaType.BOLD, (boldResult as FastPathResult.Handled).switchPersona)
    }

    @Test
    fun testNavigationFastPath() {
        val homeResult = FastPathClassifier.classify("home screen pe jao", PersonaType.GIRLFRIEND)
        assertTrue(homeResult is FastPathResult.Handled)
        val handled = homeResult as FastPathResult.Handled
        assertEquals("NAV_HOME", handled.plan.intentKey)
        assertEquals(1, handled.plan.steps.size)
        assertEquals(StepType.ACCESSIBILITY_GLOBAL, handled.plan.steps[0].type)
        assertEquals("HOME", handled.plan.steps[0].params["action"])
    }

    @Test
    fun testTaskPlanJsonSerialization() {
        val step1 = TaskStep("s1", StepType.FIND_CONTACT, mapOf("name" to "Mom"), "Mom ka number dhoond rahe hain...")
        val step2 = TaskStep("s2", StepType.SEND_WHATSAPP, mapOf("contactName" to "Mom", "message" to "Hello"), "Message bhej rahe hain...")
        val plan = TaskPlan(
            originalQuery = "Mom ko WhatsApp karo",
            intentKey = "WHATSAPP_SEND",
            steps = listOf(step1, step2),
            speechResponseHinglish = "Mom ko message bhej rahi hoon!"
        )

        val jsonStr = plan.toJsonString()
        val deserialized = TaskPlan.fromJsonString(jsonStr)

        assertNotNull(deserialized)
        assertEquals(plan.originalQuery, deserialized?.originalQuery)
        assertEquals(plan.intentKey, deserialized?.intentKey)
        assertEquals(2, deserialized?.steps?.size)
        assertEquals(StepType.FIND_CONTACT, deserialized?.steps?.get(0)?.type)
        assertEquals(StepType.SEND_WHATSAPP, deserialized?.steps?.get(1)?.type)
    }
}
