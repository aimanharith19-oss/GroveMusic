package com.grove.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes

/** Where the sound goes and how: bit-perfect USB DAC output and the hi-res (24-bit / float) path. */
object AudioOut {
    /** what the now-playing screen shows, e.g. "USB DAC · bit-perfect 96 kHz 24-bit" */
    val status = androidx.compose.runtime.mutableStateOf("")

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun output(ctx: Context): AudioDeviceInfo? {
        val am = ctx.getSystemService(AudioManager::class.java)
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
            ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
    }

    private fun kind(d: AudioDeviceInfo?) = when (d?.type) {
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB DAC"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE Audio"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "headphones"
        else -> "speaker"
    }

    private fun bits(enc: Int) = when (enc) {
        AudioFormat.ENCODING_PCM_16BIT -> 16; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT -> 32; AudioFormat.ENCODING_PCM_FLOAT -> 32; else -> 0
    }

    /**
     * Called whenever the song or output changes. [rate]/[srcBits] describe the song, [floatOut] whether the
     * player hands over float samples. Returns true when bit-perfect is active (volume must then stay at 100%).
     */
    fun update(ctx: Context, prefs: Prefs, rate: Int, srcBits: Int, floatOut: Boolean, label: String): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        val dev = output(ctx)
        val src = buildString {
            if (label.isNotBlank()) append(label) else if (rate > 0) append("${if (srcBits > 0) "$srcBits-bit " else ""}${trim(rate / 1000.0)} kHz")
        }
        val usb = dev != null && (dev.type == AudioDeviceInfo.TYPE_USB_HEADSET || dev.type == AudioDeviceInfo.TYPE_USB_DEVICE)
        var perfect = false
        if (usb) runCatching {
            if (!prefs.bitPerfect || rate <= 0) am.clearPreferredMixerAttributes(attrs, dev!!)
            else {
                val list = am.getSupportedMixerAttributes(dev!!).filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
                val wantEnc = if (floatOut) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
                val pick = list.firstOrNull { it.format.sampleRate == rate && it.format.encoding == wantEnc }
                    ?: list.firstOrNull { it.format.sampleRate == rate }
                if (pick != null) { perfect = am.setPreferredMixerAttributes(attrs, dev, pick) }
                else am.clearPreferredMixerAttributes(attrs, dev)
            }
        }
        status.value = listOf(kind(dev), when {
            perfect -> "bit-perfect $src"
            usb && prefs.bitPerfect -> "$src · this DAC can't do bit-perfect at this rate"
            floatOut && srcBits > 16 -> "hi-res $src"
            else -> src
        }).filter { it.isNotBlank() }.joinToString(" · ")
        return perfect
    }

    private fun trim(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)
}
