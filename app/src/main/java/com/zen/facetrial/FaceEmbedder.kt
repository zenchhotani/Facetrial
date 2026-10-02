package com.zen.facetrial

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Turns a cropped face into a short list of numbers (an "embedding").
 * The same person gives similar numbers; different people give different ones.
 * Input size and output length are read from the model, so different
 * FaceNet / MobileFaceNet files work without code changes.
 */
class FaceEmbedder(context: Context) {

    private val interpreter: Interpreter
    private val inputSize: Int
    private val outputSize: Int

    init {
        val bytes = context.assets.open("face_model.tflite").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
        inputSize = interpreter.getInputTensor(0).shape()[1]
        outputSize = interpreter.getOutputTensor(0).shape()[1]
    }

    @Synchronized
    fun embed(face: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(face, inputSize, inputSize, true)
        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val input = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
            .order(ByteOrder.nativeOrder())
        for (p in pixels) {
            input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
            input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        input.rewind()

        val output = Array(1) { FloatArray(outputSize) }
        interpreter.run(input, output)
        return normalize(output[0])
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum).coerceAtLeast(1e-10f)
        return FloatArray(v.size) { v[it] / norm }
    }

    fun close() {
        interpreter.close()
    }
}
