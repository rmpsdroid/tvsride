package dev.tvsopen.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class JupiterNotificationFrameTest {
    private val driver = JupiterDriver(VehicleModel.JUPITER_110)

    @Test
    fun smsFallbackUsesSupportedFirstTextRowInsteadOfExperimental053() {
        val frame = driver.buildSms("Incoming message")!!
        assertEquals(0x5B, frame[0].toInt() and 0xFF)
        assertEquals(OutboundId.TEXT_ROW1, frame[1].toInt() and 0xFF)
        assertEquals(20, frame.size)
    }

    @Test
    fun messageSenderAndBodyCanUseSeparateClusterRows() {
        val sender = driver.buildTextRow(0, "Alice")!!
        val body = driver.buildTextRow(1, "See you soon")!!

        assertEquals(OutboundId.TEXT_ROW1, sender[1].toInt() and 0xFF)
        assertEquals(OutboundId.TEXT_ROW2, body[1].toInt() and 0xFF)
        assertEquals(20, sender.size)
        assertEquals(20, body.size)
    }
}
