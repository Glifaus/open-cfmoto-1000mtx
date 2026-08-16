package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mtx1000ConfigTest {
    @Test
    fun dedicatedBuildPinsMtxProfileAndP2p() {
        assertEquals(ProfileOverride.CFDL26_PORT, Mtx1000Config.profile)
        assertEquals(Cfdl26PortraitProfile, Mtx1000Config.profile.resolve())
        assertEquals(WifiTransport.P2P, Mtx1000Config.transport)
        assertTrue(Mtx1000Config.forceNonTouch)
        assertFalse(Mtx1000Config.logTrips)
    }
}
