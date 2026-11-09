package com.jarvis.assistant.automation.command

import org.junit.Assert.*
import org.junit.Test

class MobileUtilityCommandParserTest {
    @Test fun parsesAlarm24Hour() {
        val command = CommandParser.parse("set alarm for 07:30").command
        assertEquals(JarvisCommand.SetAlarm(7, 30), command)
    }

    @Test fun parsesAlarm12Hour() {
        val command = CommandParser.parse("alarm at 7:30 pm").command
        assertEquals(JarvisCommand.SetAlarm(19, 30), command)
    }

    @Test fun rejectsInvalidAlarm() {
        assertFalse(CommandParser.parse("alarm at 27:90").command is JarvisCommand.SetAlarm)
    }

    @Test fun parsesTimer() {
        assertEquals(JarvisCommand.SetTimer(300), CommandParser.parse("set timer for 5 minutes").command)
    }

    @Test fun rejectsOverlongTimer() {
        assertFalse(CommandParser.parse("timer for 25 hours").command is JarvisCommand.SetTimer)
    }

    @Test fun composesSmsWithoutSending() {
        assertEquals(
            JarvisCommand.ComposeSms("9876543210", "hello there"),
            CommandParser.parse("compose sms to 9876543210 saying hello there").command
        )
    }

    @Test fun doesNotMistakeChatForSms() {
        assertFalse(CommandParser.parse("tell me about sms security").command is JarvisCommand.ComposeSms)
    }
}
