package com.example.lab15

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private val audioFilePath: String by lazy {
        "${externalCacheDir?.absolutePath}/audio_record.pcm"
    }

    private lateinit var audioRecord: AudioRecord
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (!isGranted) {
                Log.e("AudioPermission", "Microphone permission not granted")
            }
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            AudioRecorderApp()
        }
    }

    @Composable
    fun AudioRecorderApp() {
        var isRecording by remember { mutableStateOf(false) }
        var isPlaying by remember { mutableStateOf(false) }
        var isSaved by remember { mutableStateOf(false) }
        var playbackProgress by remember { mutableFloatStateOf(0f) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = if (isSaved) "Audio saved at: $audioFilePath" else "No audio saved yet.")
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (!isRecording) {
                        Log.d("AudioRecorder", "Starting recording")
                        startRecording()
                        isSaved = false
                    } else {
                        Log.d("AudioRecorder", "Stopping recording")
                        stopRecording()
                        isSaved = true
                    }
                    isRecording = !isRecording
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Text(if (isRecording) "Stop Recording" else "Start Recording")
            }

            Button(
                onClick = {
                    if (!isPlaying) {
                        Log.d("AudioRecorder", "Starting playback")
                        playRecording { progress ->
                            playbackProgress = progress
                        }
                    }
                    isPlaying = true
                },
                enabled = !isRecording && isSaved,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Text("Play Recording")
            }

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { playbackProgress },
                modifier = Modifier.fillMaxWidth(),
            )

            if (isPlaying) {
                Text(text = "Playing audio...")
            }
        }
    }

    private fun startRecording() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        isRecording = true
        audioRecord.startRecording()

        CoroutineScope(Dispatchers.IO).launch {
            Log.d("AudioRecorder", "Recording started")
            writeAudioDataToFile()
        }
    }

    private fun writeAudioDataToFile() {
        val audioData = ByteArray(bufferSize)
        val fileOutputStream = FileOutputStream(File(audioFilePath))

        while (isRecording) {
            val readSize = audioRecord.read(audioData, 0, audioData.size)
            if (readSize > 0) {
                fileOutputStream.write(audioData, 0, readSize)
                Log.d("AudioRecorder", "Writing data: $readSize bytes")
            }
        }
        fileOutputStream.close()
        Log.d("AudioRecorder", "Recording saved to file")
    }

    private fun stopRecording() {
        if (isRecording) {
            isRecording = false
            audioRecord.stop()
            audioRecord.release()
            Log.d("AudioRecorder", "Recording stopped")
        }
    }

    private fun playRecording(onProgress: (Float) -> Unit) {
        val file = File(audioFilePath)
        if (!file.exists()) {
            Log.e("Playback", "Audio file not found")
            return
        }

        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        CoroutineScope(Dispatchers.IO).launch {
            audioTrack.play()
            Log.d("AudioRecorder", "Playback started")

            val fileInputStream = FileInputStream(file)
            val audioData = ByteArray(bufferSize)
            val totalSize = file.length()
            var totalRead = 0L

            var readSize: Int
            while (fileInputStream.read(audioData).also { readSize = it } > 0) {
                val result = audioTrack.write(audioData, 0, readSize)
                if (result == AudioTrack.ERROR || result == AudioTrack.ERROR_BAD_VALUE) {
                    Log.e("Playback", "AudioTrack write error: $result")
                    break
                }
                totalRead += readSize
                onProgress(totalRead / totalSize.toFloat())
                Log.d("AudioRecorder", "Playing data: $readSize bytes")
            }
            fileInputStream.close()

            audioTrack.stop()
            audioTrack.release()
            Log.d("AudioRecorder", "Playback finished")

            onProgress(1f)
        }
    }
}