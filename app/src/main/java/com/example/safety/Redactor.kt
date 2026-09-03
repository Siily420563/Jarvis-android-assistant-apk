package com.example.safety

object Redactor {

    // Matches standard 13-19 digit card numbers (with or without spaces/dashes)
    private val CREDIT_CARD_REGEX = Regex("\\b(?:\\d[ -]*?){13,19}\\b")

    // Matches 6-digit OTP codes or PINs surrounded by common OTP keywords
    private val OTP_REGEX = Regex("(?i)\\b(?:otp|code|pin|verification)\\s*(?:is|:)?\\s*([0-9]{4,8})\\b")

    // Matches CVV (3 or 4 digits preceded by cvv/cvc)
    private val CVV_REGEX = Regex("(?i)\\b(?:cvv|cvc)\\s*[:=]?\\s*([0-9]{3,4})\\b")

    fun redactSensitiveText(input: String): String {
        if (input.isBlank()) return input

        var result = input
        result = CREDIT_CARD_REGEX.replace(result) { match ->
            val digitsOnly = match.value.filter { it.isDigit() }
            if (digitsOnly.length in 13..19) "[REDACTED_CARD]" else match.value
        }

        result = OTP_REGEX.replace(result) { match ->
            match.value.replace(match.groupValues[1], "[REDACTED_OTP]")
        }

        result = CVV_REGEX.replace(result) { match ->
            match.value.replace(match.groupValues[1], "[REDACTED_CVV]")
        }

        return result
    }
}
