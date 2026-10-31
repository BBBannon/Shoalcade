package com.swordfish.libretrodroid3ds

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShaderSensorTest {
    @Test
    fun shaderChainsTrackSensorUsageAndRedrawAcrossReplacements() {
        System.loadLibrary("libretrodroid3ds")
        for (hardware in listOf(false, true)) {
            assertNull("hardware=$hardware", verifyShaderChain(hardware))
        }
    }

    private external fun verifyShaderChain(hardware: Boolean): String?
}
