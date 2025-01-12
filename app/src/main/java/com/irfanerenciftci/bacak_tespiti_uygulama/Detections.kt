package com.irfanerenciftci.bacak_tespiti_uygulama

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import android.util.Log


class Detections(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String,
    private val detectorListener: DetectionResults,
) {

    //tahmin sonucuna göre çalışacak fonksiyonların interface'i

    interface DetectionResults {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    //değerler burada tanımlı

    companion object {
        private const val inputMean = 0f
        private const val standartDeviation = 255f
        private val imageType = DataType.FLOAT32
        private val outputImageType = DataType.FLOAT32
        private const val confidenceScore = 0.6F
        private const val iouThreshold = 0.5F
    }

    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(inputMean, standartDeviation))
        .add(CastOp(imageType))
        .build()

    //constructor

    init {

        val options = Interpreter.Options().apply {
            this.setNumThreads(4) // 4 çekirdek kullanımı
        }


        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)


        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        if (inputShape != null) {
            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]


            if (inputShape[1] == 3) {
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            }
        }

        if (outputShape != null) {
            numChannel = outputShape[1]
            numElements = outputShape[2]
        }


        try {
            val inputStream: InputStream = context.assets.open(labelPath)
            val reader = BufferedReader(InputStreamReader(inputStream))

            var line: String? = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                labels.add(line)
                line = reader.readLine()
            }

            reader.close()
            inputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


    //interpreter'ı yeniden başlatma

    fun restart() {
        interpreter.close()

        val options = Interpreter.Options().apply {
            this.setNumThreads(4)
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)
    }

    //interpreter'ı kapatma

    fun close() {
        interpreter.close()
    }

    //tahmin etme fonksiyonu

    fun detect(frame: Bitmap) {
        try {
            if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) {
                throw IllegalStateException("Model input/output dimensions are not properly initialized.")
            }

            var inferenceTime = SystemClock.uptimeMillis()

            val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)

            val tensorImage = TensorImage(imageType).apply {
                load(resizedBitmap)
            }
            val processedImage = imageProcessor.process(tensorImage)

            val imageBuffer = processedImage.buffer

            val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), outputImageType)

            interpreter.run(imageBuffer, output.buffer)

            val bestBoxes = bestBoundingBoxes(output.floatArray)
            inferenceTime = SystemClock.uptimeMillis() - inferenceTime

            if (bestBoxes.isNullOrEmpty()) {
                detectorListener.onEmptyDetect()
            } else {
                detectorListener.onDetect(bestBoxes, inferenceTime)
            }
        } catch (e: Exception) {
            Log.e("Detection", "Error during detection: ${e.message}", e)
            detectorListener.onEmptyDetect() // Hata durumunda boş sonuç döndür
        }
    }

    //tahmin değerine en çok yaklaşan bounding boxlar seçilir.

    private fun bestBoundingBoxes(array: FloatArray) : List<BoundingBox>? {

        val boundingBoxes = mutableListOf<BoundingBox>()

        for (c in 0 until numElements) {

            var maxConf = confidenceScore
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j
            while (j < numChannel){
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }

            if (maxConf > confidenceScore) {
                val clsName = labels[maxIdx]

                val cx = array[c] // 0
                val cy = array[c + numElements] // 1
                val w = array[c + numElements * 2]
                val h = array[c + numElements * 3]

                val x1 = cx - (w/2F)
                val y1 = cy - (h/2F)
                val x2 = cx + (w/2F)
                val y2 = cy + (h/2F)


                if (x1 < 0F || x1 > 1F) continue
                if (y1 < 0F || y1 > 1F) continue
                if (x2 < 0F || x2 > 1F) continue
                if (y2 < 0F || y2 > 1F) continue

                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        score = maxConf, classIndex = maxIdx, className = clsName
                    )
                )
            }
        }

        if (boundingBoxes.isEmpty()) return null

        return refineGroups(boundingBoxes)
    }

    //benzer beya çakışan bounding boxları eleme. (NMS)

    private fun refineGroups(boxes: List<BoundingBox>) : MutableList<BoundingBox> {

        val refinedBoxes = mutableListOf<BoundingBox>()
        val sortedBoxes = boxes.sortedByDescending { it.score }

        sortedBoxes.forEach { box ->
            if (refinedBoxes.none { calculateIoU(it, box)["iou"] ?: 0f > 0.5 }) {
                refinedBoxes.add(box)
            }
        }

        return refinedBoxes
    }

    //iou metriği ölçülür. tahmin değerini ne kadar iyi yaptığını bu fonksiyon sayesinde ölçebiliriz.

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Map<String, Float> {

        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h

        val iou = intersectionArea / (box1Area + box2Area - intersectionArea)
        val overlapRatio1 = intersectionArea / box1Area
        val overlapRatio2 = intersectionArea / box2Area

        return mapOf(
            "iou" to iou,
            "overlapBox1" to overlapRatio1,
            "overlapBox2" to overlapRatio2)
    }




}