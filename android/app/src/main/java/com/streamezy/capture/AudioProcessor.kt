package com.streamezy.capture

import com.pedro.encoder.input.audio.CustomAudioEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

enum class AudioSourceType {
    MOBILE,      // Built-in phone microphone
    EXTERNAL,    // OTG / USB capture card or USB mic
    CUSTOM       // Background custom audio track
}

/**
 * Real-time DSP audio processor for RootEncoder pipeline.
 * - Live microphone mute (for 100% clean background music playback)
 * - Continuous noise reduction slider (0% to 100%) with spectral rumble & hiss filter
 * - Real-time background audio mixer (MP3/WAV/AAC decoded PCM) with volume & looping
 * - Master volume control (0% to 100%) & non-intrusive Master Mute
 * - Active source routing (Mobile, External OTG/USB, Custom)
 */
class AudioProcessor : CustomAudioEffect() {

    @Volatile var activeSource: AudioSourceType = AudioSourceType.MOBILE
    @Volatile var masterVolume: Float = 0.75f // Default 75% master volume
    @Volatile var isMasterMuted: Boolean = false

    @Volatile var isLiveMuted: Boolean = false
    @Volatile var micVolume: Float = 1.0f
    @Volatile var noiseReductionPercent: Int = 50 // Default 50%
    @Volatile var bgVolume: Float = 0.7f // Default 70%
    @Volatile var isBgPlaying: Boolean = false
    @Volatile var isLooping: Boolean = true

    // Decoded background PCM audio samples (44100Hz 16-bit mono)
    private var bgSamples: ShortArray? = null
    private var bgSampleIndex: Int = 0

    // Smooth envelope state for noise reduction gate
    private var smoothedEnvelope: Float = 0f
    // High-pass filter state for low-frequency rumble removal
    private var lastInputSample: Float = 0f
    private var lastOutputSample: Float = 0f

    fun setBackgroundAudio(samples: ShortArray) {
        synchronized(this) {
            bgSamples = samples
            bgSampleIndex = 0
            isBgPlaying = true
        }
    }

    fun clearBackgroundAudio() {
        synchronized(this) {
            bgSamples = null
            bgSampleIndex = 0
            isBgPlaying = false
        }
    }

    fun playBackground() {
        if (bgSamples != null) {
            isBgPlaying = true
        }
    }

    fun pauseBackground() {
        isBgPlaying = false
    }

    fun stopBackground() {
        isBgPlaying = false
        synchronized(this) {
            bgSampleIndex = 0
        }
    }

    val isPlaying: Boolean
        get() = isBgPlaying

    val currentPositionSeconds: Int
        get() = synchronized(this) {
            if (bgSamples != null && bgSamples!!.isNotEmpty()) (bgSampleIndex / 44100) else 0
        }

    val totalDurationSeconds: Int
        get() = synchronized(this) {
            if (bgSamples != null && bgSamples!!.isNotEmpty()) (bgSamples!!.size / 44100) else 0
        }

    fun hasBackgroundAudio(): Boolean {
        return bgSamples != null && bgSamples!!.isNotEmpty()
    }

    override fun process(pcmBuffer: ByteArray): ByteArray {
        val numSamples = pcmBuffer.size / 2
        if (numSamples <= 0) return pcmBuffer

        val shortBuffer = ByteBuffer.wrap(pcmBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val tempShorts = ShortArray(numSamples)
        shortBuffer.get(tempShorts)

        val noiseRatio = (noiseReductionPercent.coerceIn(0, 100)) / 100f
        // Dynamic threshold: at 100%, threshold is ~2200 amplitude; at 50%, ~1100 amplitude
        val noiseThreshold = noiseRatio * 2200f

        synchronized(this) {
            val bg = bgSamples
            val playing = isBgPlaying && bg != null && bg.isNotEmpty()

            for (i in 0 until numSamples) {
                // 1. Process Live Microphone (Active for Mobile & External OTG/USB sources, suppressed if Custom Audio is selected or if live-muted)
                var micVal: Float = 0f
                if (activeSource != AudioSourceType.CUSTOM && !isLiveMuted) {
                    val rawSample = tempShorts[i].toFloat()

                    if (noiseRatio > 0f) {
                        // High-pass IIR filter (alpha=0.95) to cut air-con / wind rumble (< 120Hz)
                        val hpAlpha = 0.95f
                        val hpSample = hpAlpha * (lastOutputSample + rawSample - lastInputSample)
                        lastInputSample = rawSample
                        lastOutputSample = hpSample

                        // Compute instantaneous envelope with fast attack and slower release
                        val mag = abs(hpSample)
                        val alpha = if (mag > smoothedEnvelope) 0.2f else 0.02f
                        smoothedEnvelope += alpha * (mag - smoothedEnvelope)

                        if (smoothedEnvelope < noiseThreshold) {
                            // Below noise floor: suppress noise proportionally to noiseRatio
                            val attenuation = (1f - noiseRatio * (1f - (smoothedEnvelope / max(1f, noiseThreshold))))
                                .coerceIn(0f, 1f)
                            micVal = hpSample * attenuation * micVolume
                        } else {
                            micVal = hpSample * micVolume
                        }
                    } else {
                        micVal = rawSample * micVolume
                    }
                }

                // 2. Process Background Music / Audio
                var bgVal: Float = 0f
                if (playing && bg != null) {
                    if (bgSampleIndex < bg.size) {
                        bgVal = bg[bgSampleIndex].toFloat() * bgVolume
                        bgSampleIndex++
                    } else if (isLooping) {
                        bgSampleIndex = 0
                        bgVal = bg[0].toFloat() * bgVolume
                        bgSampleIndex++
                    } else {
                        isBgPlaying = false
                    }
                }

                // 3. Mix & Apply Master Volume & Master Mute (-32768 to 32767)
                val mixed = if (isMasterMuted) {
                    0
                } else {
                    ((micVal + bgVal) * masterVolume).toInt().coerceIn(-32768, 32767)
                }
                tempShorts[i] = mixed.toShort()
            }
        }

        // Write processed samples back into pcmBuffer
        ByteBuffer.wrap(pcmBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(tempShorts)
        return pcmBuffer
    }
}
