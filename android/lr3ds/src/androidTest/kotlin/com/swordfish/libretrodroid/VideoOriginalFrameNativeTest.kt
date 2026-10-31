package com.swordfish.libretrodroid3ds

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoOriginalFrameNativeTest {

    @Test
    fun hardwareTextureAllocationDiffersFromActiveImage() {
        System.loadLibrary("libretrodroid3ds")
        assertTrue(renderPaddedHardwareFrame())
    }

    private external fun renderPaddedHardwareFrame(): Boolean
}
