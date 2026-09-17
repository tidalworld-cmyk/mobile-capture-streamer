package com.streamezy.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance native audio decoder.
 * Uses Android MediaExtractor + MediaCodec to decode any audio file (MP3, WAV, AAC, M4A, OGG)
 * into 44.1kHz 16-bit mono PCM samples for seamless live broadcast mixing.
 */
object AudioDecoder {
    private const val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 44100

    suspend fun decodeToPcm(context: Context, uri: Uri): ShortArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("Cannot open file descriptor for audio")
            extractor.setDataSource(pfd.fileDescriptor)
            pfd.close()

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                throw IllegalArgumentException("No valid audio track found in file")
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else TARGET_SAMPLE_RATE
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val decodedRawList = ArrayList<Short>()
            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            val timeoutUs = 5000L

            while (!isEos) {
                val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                while (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                        if (channelCount >= 2) {
                            // Downmix stereo to mono
                            while (shortBuf.hasRemaining()) {
                                val left = shortBuf.get().toInt()
                                val right = if (shortBuf.hasRemaining()) shortBuf.get().toInt() else left
                                decodedRawList.add(((left + right) / 2).toShort())
                            }
                        } else {
                            while (shortBuf.hasRemaining()) {
                                decodedRawList.add(shortBuf.get())
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                }
            }

            val rawArray = ShortArray(decodedRawList.size) { decodedRawList[it] }
            if (sourceSampleRate == TARGET_SAMPLE_RATE || sourceSampleRate <= 0) {
                rawArray
            } else {
                resample(rawArray, sourceSampleRate, TARGET_SAMPLE_RATE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio decode failed", e)
            throw e
        } finally {
            try { codec?.stop(); codec?.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
        }
    }

    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val targetLength = (input.size * ratio).toInt()
        val output = ShortArray(targetLength)
        for (i in 0 until targetLength) {
            val srcIndex = (i / ratio).toInt().coerceIn(0, input.size - 1)
            output[i] = input[srcIndex]
        }
        return output
    }

    fun getFileName(context: Context, uri: Uri): String {
        var name = "Custom Audio"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex) ?: "Custom Audio"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve file name", e)
        }
        return name
    }
}
