package com.airplay.streamer.engine

import com.airplay.streamer.discovery.AirPlayDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class SinkFactoryTest {
    @Test
    fun sonosBookshelfUsesUnencryptedRaopBeforeAirPlay2() {
        val symfonisk = AirPlayDevice(
            name = "001122334455@Test Sonos",
            host = "192.0.2.10",
            port = 7000,
            deviceId = "00:11:22:33:44:55",
            features = mapOf(
                "manufacturer" to "Sonos",
                "model" to "Bookshelf",
                "et" to "0,4",
                "features" to "0x445F8A00,0x801C340",
            ),
            protocolVersion = 2,
            raopPort = 7000,
        )

        assertEquals(Transport.NATIVE_PTP, SinkFactory.transportFor(symfonisk))
    }
}
