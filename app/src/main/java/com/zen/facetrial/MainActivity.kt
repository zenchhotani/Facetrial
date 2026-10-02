package com.zen.facetrial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: FaceOverlay
    private lateinit var statusText: TextView
    private lateinit var nameText: TextView
    private lateinit var micButton: Button
    private lateinit var cameraExecutor: ExecutorService

    private var useFrontCamera = true

    private var speech: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                statusText.text = "Camera permission denied. Enable it in Settings > Apps > Face Trial."
            }
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                nameText.text = "Microphone permission denied. Enable it in Settings > Apps > Face Trial."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        nameText = findViewById(R.id.nameText)
        micButton = findViewById(R.id.micButton)
        cameraExecutor = Executors.newSingleThreadExecutor()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault())
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) tts?.setLanguage(Locale.US).also { ttsReady = true }
            }
        }

        findViewById<Button>(R.id.flipButton).setOnClickListener {
            useFrontCamera = !useFrontCamera
            startCamera()
        }

        micButton.setOnClickListener {
            if (listening) {
                stopListening()
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startListening()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------- Speech: listen for a name, then say it back ----------

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            nameText.text = "Speech recognition isn't available on this phone."
            return
        }
        tts?.stop()
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    nameText.text = "Listening... say your name"
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) nameText.text = partial
                }

                override fun onResults(results: Bundle?) {
                    setListening(false)
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (heard.isNullOrBlank()) {
                        nameText.text = "I didn't catch that. Try again."
                        return
                    }
                    val name = extractName(heard)
                    nameText.text = "Hello, $name!"
                    speak("Hello $name. Nice to meet you.")
                }

                override fun onError(error: Int) {
                    setListening(false)
                    nameText.text = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear a name. Try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs internet on this phone."
                        else -> "Speech error ($error). Try again."
                    }
                }
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        setListening(true)
        speech?.startListening(intent)
    }

    private fun stopListening() {
        speech?.stopListening()
        setListening(false)
    }

    private fun setListening(value: Boolean) {
        listening = value
        micButton.text = if (value) "Stop" else "Say your name"
    }

    /** "my name is Zen" -> "Zen" */
    private fun extractName(heard: String): String {
        val cleaned = heard.trim()
            .replace(
                Regex(
                    "^(hi|hello|hey)?[ ,]*(my name is|my name's|i am|i'm|im|this is|call me|it's|its)\\s+",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
        val name = if (cleaned.isEmpty()) heard.trim() else cleaned
        return name.split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { c -> c.uppercase() }
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "name")
        } else {
            Toast.makeText(this, "Text-to-speech isn't ready yet", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Camera + face detection ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) } }

            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
                statusText.text = "Looking for faces..."
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
                statusText.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val rotated = rotation == 90 || rotation == 270
        val imgW = if (rotated) proxy.height else proxy.width
        val imgH = if (rotated) proxy.width else proxy.height

        val input = InputImage.fromMediaImage(media, rotation)
        detector.process(input)
            .addOnSuccessListener { faces ->
                overlay.update(faces.map { it.boundingBox }, imgW, imgH, useFrontCamera)
                runOnUiThread {
                    statusText.text = when (faces.size) {
                        0 -> "No face detected"
                        1 -> "1 face detected"
                        else -> "${faces.size} faces detected"
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        speech?.destroy()
        tts?.shutdown()
        cameraExecutor.shutdown()
        detector.close()
    }
}
