package com.example.audiotest

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.jtransforms.fft.DoubleFFT_1D
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

class MainActivity : FlutterActivity() {

    companion object {
        private const val METHOD_CHANNEL = "example.audiotest/data"
        private const val EVENT_CHANNEL = "example.audiotest/stream"
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL
        ).setStreamHandler(
            StreamHandler
        )

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL
        ).setMethodCallHandler { call, result ->
            if (call.method == "getData") {
                val data = getData()

                if (data != "") {
                    result.success(data)
                } else {
                    result.error("UNAVAILABLE", "Data not available.", null)
                }
            } else {
                result.notImplemented()
            }
        }
    }

    private fun getData(): String {
        return "This is a test"
    }

    @SuppressLint("MissingPermission")
    object StreamHandler : EventChannel.StreamHandler {
        private var handler = Handler(Looper.getMainLooper())
        private var eventSink: EventChannel.EventSink? = null

        private const val SAMPLING_RATE_IN_HZ = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        /**
         * Factor by that the minimum buffer size is multiplied. The bigger the factor is the less
         * likely it is that samples will be dropped, but more memory will be used. The minimum
         * buffer size is determined by {@link AudioRecord#getMinBufferSize(int, int, int)} and
         * depends on the recording settings.
         */
        private const val BUFFER_SIZE_FACTOR = 2
        val BUFFER_SIZE =
            AudioRecord.getMinBufferSize(
                SAMPLING_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR
        val recordingInProgress = AtomicBoolean(false)
        var recorder: AudioRecord? = null
        private var recordingThread: Thread? = null
        val data = AtomicInteger()

        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            eventSink = events
            val r = object : Runnable {
                var counter = 0
                override fun run() {
                    handler.post {
                        counter++
                        eventSink?.success(data.get().toString())
                    }
                    handler.postDelayed(this, 100)

//                    if (counter > 600 && recordingInProgress.get()) {
//                        stopRecording()
//                    }
                    if (counter > 21 && !recordingInProgress.get()) {
                        startRecording()
                    }
                }
            }
            handler.postDelayed(r, 100)
        }

        override fun onCancel(arguments: Any?) {
            eventSink = null
        }

        fun startRecording() {
            println("__startRecording__")
            recorder = AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLING_RATE_IN_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )
            println("state is ${recorder?.state}")
            recorder?.startRecording()
            recordingInProgress.set(true)
            recordingThread = Thread(RecordingRunnable(), "Recording Thread")
            recordingThread?.start()
        }

        fun stopRecording() {
            println("__stopRecording__")
            if (null == recorder) {
                return
            }
            recordingInProgress.set(false)
            recorder?.stop()
            recorder?.release()
            recorder = null
            recordingThread = null
        }

        class RecordingRunnable : Runnable {
            override fun run() {
                try {
                    val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
                    while (recordingInProgress.get()) {
                        val result: Int = recorder?.read(buffer, BUFFER_SIZE) ?: -1
                        if (result < 0) {
                            throw RuntimeException(
                                "Reading of audio buffer failed: " +
                                        getBufferReadFailureReason(result)
                            )
                        }
                        data.set(fft(buffer.array()))
                        buffer.clear()
                    }
                } catch (e: IOException) {
                    Log.e("Recorder", "Writing of recorded audio failed", e)
                }
            }

            private fun getBufferReadFailureReason(errorCode: Int): String {
                return when (errorCode) {
                    AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                    AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                    AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                    AudioRecord.ERROR -> "ERROR"
                    else -> "Unknown ($errorCode)"
                }
            }

            // https://stackoverflow.com/questions/21799625/low-pass-android-pcm-audio-data

            private fun fft(data: ByteArray): Int {
                val bufferSize = BUFFER_SIZE
                val fft1d = DoubleFFT_1D(bufferSize.toLong())
//                val window = DoubleArray(bufferSize)
                var binNo = 0

//                for (i in 0 until bufferSize) {
//                    window[i] = ((1 - Math.cos(i*2*Math.PI/bufferSize-1))/2)
//                    data[i] = (data[i] * window[i]).toInt().toByte()
//                }
                val fftBuffer = DoubleArray(bufferSize * 2)

                for (i in 0 until bufferSize) {
                    fftBuffer[2*i] = data[i].toDouble();
                    fftBuffer[2*i+1] = 0.toDouble()
                }
                fft1d.complexForward(fftBuffer);
                val magnitude = DoubleArray(bufferSize / 2)
                var maxVal = 0

                for (i in 0 until bufferSize/2) {
                    val real = fftBuffer[2*i];
                    val imaginary = fftBuffer[2*i + 1];
                    magnitude[i] = sqrt( real*real + imaginary*imaginary );

                    for (j in 0 until bufferSize/2) {
                        if(magnitude[i] > maxVal){
                            maxVal = magnitude[i].toInt()
                            binNo = i
                        }
                    }
                }
                val freq = 8000 * binNo/(bufferSize/2)
                Log.i("freq","" + freq + "Hz");
                return freq
            }
        }
    }
}
