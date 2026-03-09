package zaujaani.roadsensecollector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * ImageStandardizer — Standarisasi foto sebelum disimpan ke class folder.
 *
 * Proses:
 * 1. Baca foto dari URI / File
 * 2. Koreksi rotasi dari EXIF
 * 3. Center crop → square
 * 4. Resize → 640×640
 * 5. Simpan JPEG quality 95
 * 6. Strip semua metadata (bersih untuk training)
 */
object ImageStandardizer {

    private const val TARGET_SIZE   = 640
    private const val JPEG_QUALITY  = 95

    /**
     * Standardize dari URI (kamera / gallery import)
     * Return: File hasil standardisasi, atau null jika gagal
     */
    fun standardize(context: Context, sourceUri: Uri, destFile: File): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return false
            val bitmap      = decodeBitmap(inputStream) ?: return false
            val rotated     = correctRotation(context, sourceUri, bitmap)
            val cropped     = centerCropToSquare(rotated)
            val resized     = Bitmap.createScaledBitmap(cropped, TARGET_SIZE, TARGET_SIZE, true)
            saveBitmap(resized, destFile)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Standardize dari File (untuk file yang sudah ada di disk)
     */
    fun standardizeFile(sourceFile: File, destFile: File): Boolean {
        return try {
            val bitmap  = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return false
            val rotated = correctRotationFromFile(sourceFile, bitmap)
            val cropped = centerCropToSquare(rotated)
            val resized = Bitmap.createScaledBitmap(cropped, TARGET_SIZE, TARGET_SIZE, true)
            saveBitmap(resized, destFile)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ── Decode bitmap dengan inSampleSize ─────────────────────────────
    private fun decodeBitmap(inputStream: InputStream): Bitmap? {
        val bytes = inputStream.readBytes()

        // Cek ukuran asli dulu
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        // Hitung sample size supaya tidak OOM
        options.inSampleSize    = calculateInSampleSize(options, TARGET_SIZE * 2, TARGET_SIZE * 2)
        options.inJustDecodeBounds = false
        options.inPreferredConfig  = Bitmap.Config.RGB_565

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth  = width  / 2
            while (halfHeight / inSampleSize >= reqHeight &&
                halfWidth  / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ── Koreksi rotasi dari EXIF (URI) ────────────────────────────────
    private fun correctRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif        = ExifInterface(inputStream)
            rotateBitmap(bitmap, getRotationDegrees(exif))
        } catch (e: Exception) { bitmap }
    }

    // ── Koreksi rotasi dari EXIF (File) ───────────────────────────────
    private fun correctRotationFromFile(file: File, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(file.absolutePath)
            rotateBitmap(bitmap, getRotationDegrees(exif))
        } catch (e: Exception) { bitmap }
    }

    private fun getRotationDegrees(exif: ExifInterface): Float {
        return when (exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else                                 -> 0f
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ── Center crop ke square ─────────────────────────────────────────
    private fun centerCropToSquare(bitmap: Bitmap): Bitmap {
        val w    = bitmap.width
        val h    = bitmap.height
        val size = minOf(w, h)
        val x    = (w - size) / 2
        val y    = (h - size) / 2

        if (w == h) return bitmap
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }

    // ── Simpan ke JPEG 95 ─────────────────────────────────────────────
    private fun saveBitmap(bitmap: Bitmap, destFile: File): Boolean {
        destFile.parentFile?.mkdirs()
        return FileOutputStream(destFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            true
        }
    }

    // ── Info helper ───────────────────────────────────────────────────
    fun getImageInfo(file: File): String {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            "${opts.outWidth}×${opts.outHeight}px  ${file.length() / 1024}KB"
        } catch (e: Exception) { "Unknown" }
    }
}