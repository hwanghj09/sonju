package com.hwanghj09.sonju.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** Bundled Korean OCR. This class has no model API, file storage, or accessibility action sink. */
object OnDevicePageReader {
    fun read(bitmap: Bitmap, callback: (Result<List<String>>) -> Unit) {
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { it.lines }
                    .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                    .map { it.text }.filter(String::isNotBlank)
                callback(Result.success(lines))
            }
            .addOnFailureListener { callback(Result.failure(it)) }
            .addOnCompleteListener { recognizer.close(); bitmap.recycle() }
    }
}
