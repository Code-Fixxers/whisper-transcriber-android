package com.whispertranscriber.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioInputDeviceSelectorTest {

    @Test
    fun prefersBluetoothHeadsetMicOverBuiltInMic() {
        val selected = AudioInputDeviceSelector.choosePreferredInput(
            listOf(
                AudioInputDevice(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_MIC, name = "Phone"),
                AudioInputDevice(id = 2, type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, name = "Headset")
            )
        )

        assertEquals(2, selected?.id)
    }

    @Test
    fun prefersWiredHeadsetOverBuiltInMicWhenBluetoothIsUnavailable() {
        val selected = AudioInputDeviceSelector.choosePreferredInput(
            listOf(
                AudioInputDevice(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_MIC, name = "Phone"),
                AudioInputDevice(id = 3, type = AudioDeviceInfo.TYPE_WIRED_HEADSET, name = "Wired")
            )
        )

        assertEquals(3, selected?.id)
    }
}
