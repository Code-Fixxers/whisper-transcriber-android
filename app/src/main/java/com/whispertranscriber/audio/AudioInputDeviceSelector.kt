package com.whispertranscriber.audio

import android.media.AudioDeviceInfo

data class AudioInputDevice(
    val id: Int,
    val type: Int,
    val name: String
)

object AudioInputDeviceSelector {
    fun choosePreferredInput(devices: List<AudioInputDevice>): AudioInputDevice? {
        val priority = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BUILTIN_MIC
        )
        return devices.minByOrNull { device ->
            priority.indexOf(device.type).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
    }
}
