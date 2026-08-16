package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class AaMarginsTest {
    @Test
    fun mtxMarginsMatchEncodedCanvas() {
        val canvas = Cfdl26PortraitProfile.roundCaptureDimensions(800, 951)
        assertEquals(800 to 944, canvas)
        assertEquals(
            AaMargins(0, 430),
            AaMargins.forAspect(Cfdl26PortraitProfile.aaVideo, canvas.first, canvas.second),
        )
        assertEquals(
            AaMargins(0, 646),
            AaMargins.forAspect(
                AaVideoSpec(AaResolution.PORTRAIT_1080x1920, dpi = 360),
                canvas.first,
                canvas.second,
            ),
        )
    }
}
