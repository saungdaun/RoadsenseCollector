package zaujaani.roadsensecollector

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.min

/**
 * CropActivity — fullscreen crop dengan drag handles di 4 sudut + sisi.
 *
 * Input  : EXTRA_IMAGE_PATH  = path file JPEG sumber
 * Output : EXTRA_CROP_PATH   = path file JPEG hasil crop (di cacheDir)
 *          Result = RESULT_OK jika berhasil, RESULT_CANCELED jika batal
 *
 * Hasil crop di-standardize ke 640×640 (padding hitam, aspect ratio preserved)
 * agar siap untuk training YOLO.
 */
class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        const val EXTRA_CROP_PATH  = "extra_crop_path"

        fun intent(context: Context, imagePath: String): Intent =
            Intent(context, CropActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_PATH, imagePath)
            }
    }

    private lateinit var imgView    : ImageView
    private lateinit var cropOverlay: CropOverlayView
    private lateinit var tvHint     : TextView
    private lateinit var btnCrop    : Button
    private lateinit var btnCancel  : Button
    private lateinit var btnReset   : Button

    private var sourceBitmap: Bitmap? = null
    private var imagePath   : String  = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen — use WindowInsetsController on API 30+, legacy flags otherwise
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: run {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        sourceBitmap = BitmapFactory.decodeFile(imagePath) ?: run {
            Toast.makeText(this, getString(R.string.crop_error_open), Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        buildUI()
    }

    private fun buildUI() {
        val bmp = sourceBitmap ?: return

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // ImageView background
        imgView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
        }

        // Crop overlay di atas image
        cropOverlay = CropOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Hint text
        tvHint = TextView(this).apply {
            setText(R.string.crop_hint)
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0x88000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 0 }
        }

        // Bottom bar
        val bottomBar = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setPadding(16, 12, 16, 12)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = android.view.Gravity.BOTTOM
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnCancel = Button(this).apply {
            setText(R.string.crop_btn_cancel)
            setBackgroundColor(0xFF555555.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.marginEnd = 8 }
        }

        btnReset = Button(this).apply {
            setText(R.string.crop_btn_reset)
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.marginEnd = 8 }
        }

        btnCrop = Button(this).apply {
            setText(R.string.crop_btn_crop)
            setBackgroundColor(0xFF2E7D32.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f
            )
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnReset)
        btnRow.addView(btnCrop)
        bottomBar.addView(btnRow)

        root.addView(imgView)
        root.addView(cropOverlay)
        root.addView(tvHint)
        root.addView(bottomBar)
        setContentView(root)

        // Tunggu layout selesai baru inisialisasi crop rect
        cropOverlay.post {
            cropOverlay.initCropRect()
        }

        // Button actions
        btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        btnReset.setOnClickListener {
            cropOverlay.resetCropRect()
        }

        btnCrop.setOnClickListener {
            performCrop()
        }
    }

    private fun performCrop() {
        val bmp = sourceBitmap ?: return
        val cropNorm = cropOverlay.getCropRectNormalized() ?: run {
            Toast.makeText(this, getString(R.string.crop_error_invalid), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Konversi normalized rect ke pixel koordinat bitmap
            val bmpW = bmp.width.toFloat()
            val bmpH = bmp.height.toFloat()

            val left   = (cropNorm.left   * bmpW).toInt().coerceIn(0, bmp.width - 1)
            val top    = (cropNorm.top    * bmpH).toInt().coerceIn(0, bmp.height - 1)
            val right  = (cropNorm.right  * bmpW).toInt().coerceIn(left + 1, bmp.width)
            val bottom = (cropNorm.bottom * bmpH).toInt().coerceIn(top + 1, bmp.height)

            val cropW  = right - left
            val cropH  = bottom - top

            if (cropW < 32 || cropH < 32) {
                Toast.makeText(this, getString(R.string.crop_error_too_small), Toast.LENGTH_SHORT).show()
                return
            }

            val cropped = Bitmap.createBitmap(bmp, left, top, cropW, cropH)

            // Standardize ke 640×640 dengan padding hitam (sama seperti ImageStandardizer)
            val size     = 640
            val scale    = min(size.toFloat() / cropW, size.toFloat() / cropH)
            val scaledW  = (cropW * scale).toInt()
            val scaledH  = (cropH * scale).toInt()
            val offsetX  = (size - scaledW) / 2
            val offsetY  = (size - scaledH) / 2

            // Preallocate paint for canvas draw (fix: avoid allocation in draw)
            val output   = createBitmap(size, size)
            val canvas   = Canvas(output)
            canvas.drawColor(Color.BLACK)

            val scaled = cropped.scale(scaledW, scaledH)      // KTX extension
            canvas.drawBitmap(scaled, offsetX.toFloat(), offsetY.toFloat(), null)

            cropped.recycle()
            scaled.recycle()

            // Simpan ke cacheDir
            val outFile = File(cacheDir, "crop_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { fos ->
                output.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
            output.recycle()

            val result = Intent().apply {
                putExtra(EXTRA_CROP_PATH, outFile.absolutePath)
            }
            setResult(Activity.RESULT_OK, result)
            finish()

        } catch (e: Exception) {
            Toast.makeText(this,
                getString(R.string.crop_error_generic, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sourceBitmap?.recycle()
        sourceBitmap = null
    }
}

/**
 * CropOverlayView — overlay transparan dengan crop box yang bisa di-drag.
 *
 * Handle aktif:
 *  - 4 sudut (resize diagonal)
 *  - 4 sisi tengah (resize horizontal/vertikal)
 *  - Dalam kotak (geser/move)
 *
 * Crop rect disimpan dalam koordinat normalized (0..1) relatif terhadap
 * view size, karena ImageView bisa punya padding/letterbox.
 */
class CropOverlayView(context: Context) : View(context) {

    // Crop rect dalam koordinat VIEW (pixel)
    private val cropRect = RectF()

    // Batas area gambar dalam koordinat VIEW (letterbox aware)
    private var imageRect = RectF()

    // Fix: no underscores in private property names (use camelCase)
    private val handleRadius = 28f
    private val minSize      = 80f

    private enum class DragMode {
        NONE,
        MOVE,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
        TOP, BOTTOM, LEFT, RIGHT
    }

    private var dragMode   = DragMode.NONE
    private var dragStart  = PointF()
    private var cropStart  = RectF()

    // ── Paint (preallocated — fix: avoid allocation during draw) ──────
    private val dimPaint = Paint().apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val handleBorderPaint = Paint().apply {
        color = 0xFF333333.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    // Preallocated info paint — fix: was allocated inside onDraw()
    private val infoPaint = Paint().apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 28f
        isAntiAlias = true
    }

    // ── Init ──────────────────────────────────────────────────────────
    fun initCropRect() {
        computeImageRect()
        // Default: 80% dari area gambar, di tengah
        val padX = imageRect.width()  * 0.10f
        val padY = imageRect.height() * 0.10f
        cropRect.set(
            imageRect.left   + padX,
            imageRect.top    + padY,
            imageRect.right  - padX,
            imageRect.bottom - padY
        )
        invalidate()
    }

    fun resetCropRect() {
        initCropRect()
    }

    /**
     * Hitung posisi gambar aktual di dalam ImageView (FIT_CENTER = letterbox).
     */
    private fun computeImageRect() {
        val parent = parent as? FrameLayout
        val imgView = parent?.getChildAt(0) as? ImageView ?: run {
            imageRect.set(0f, 0f, width.toFloat(), height.toFloat())
            return
        }
        val drawable = imgView.drawable ?: run {
            imageRect.set(0f, 0f, width.toFloat(), height.toFloat())
            return
        }

        val dW   = drawable.intrinsicWidth.toFloat()
        val dH   = drawable.intrinsicHeight.toFloat()
        val vW   = imgView.width.toFloat()
        val vH   = imgView.height.toFloat()

        if (dW <= 0 || dH <= 0 || vW <= 0 || vH <= 0) {
            imageRect.set(0f, 0f, width.toFloat(), height.toFloat())
            return
        }

        val scale   = min(vW / dW, vH / dH)
        val scaledW = dW * scale
        val scaledH = dH * scale
        val left    = (vW - scaledW) / 2f
        val top     = (vH - scaledH) / 2f

        imageRect.set(left, top, left + scaledW, top + scaledH)
    }

    /**
     * Kembalikan crop rect dalam koordinat NORMALIZED terhadap gambar asli.
     * (0,0) = pojok kiri atas gambar, (1,1) = pojok kanan bawah gambar.
     */
    fun getCropRectNormalized(): RectF? {
        if (imageRect.isEmpty || cropRect.isEmpty) return null
        val iW = imageRect.width()
        val iH = imageRect.height()
        if (iW <= 0 || iH <= 0) return null
        return RectF(
            (cropRect.left   - imageRect.left) / iW,
            (cropRect.top    - imageRect.top)  / iH,
            (cropRect.right  - imageRect.left) / iW,
            (cropRect.bottom - imageRect.top)  / iH
        ).also { r ->
            r.left   = r.left.coerceIn(0f, 1f)
            r.top    = r.top.coerceIn(0f, 1f)
            r.right  = r.right.coerceIn(0f, 1f)
            r.bottom = r.bottom.coerceIn(0f, 1f)
        }
    }

    // ── Draw ──────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cropRect.isEmpty) return

        val w = width.toFloat()
        val h = height.toFloat()

        // Dim area luar crop
        canvas.drawRect(0f, 0f, w, cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, w, h, dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, w, cropRect.bottom, dimPaint)

        // Grid rule-of-thirds
        val thirdW = cropRect.width()  / 3f
        val thirdH = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + thirdW, cropRect.top, cropRect.left + thirdW, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + thirdW * 2, cropRect.top, cropRect.left + thirdW * 2, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH, cropRect.right, cropRect.top + thirdH, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH * 2, cropRect.right, cropRect.top + thirdH * 2, gridPaint)

        // Border
        canvas.drawRect(cropRect, borderPaint)

        // Handles
        drawHandle(canvas, cropRect.left,      cropRect.top)
        drawHandle(canvas, cropRect.right,     cropRect.top)
        drawHandle(canvas, cropRect.left,      cropRect.bottom)
        drawHandle(canvas, cropRect.right,     cropRect.bottom)
        drawHandle(canvas, cropRect.centerX(), cropRect.top)
        drawHandle(canvas, cropRect.centerX(), cropRect.bottom)
        drawHandle(canvas, cropRect.left,      cropRect.centerY())
        drawHandle(canvas, cropRect.right,     cropRect.centerY())

        // Ukuran crop (informasi) — uses preallocated infoPaint
        val norm = getCropRectNormalized()
        if (norm != null) {
            val cw = (norm.width()  * 100).toInt()
            val ch = (norm.height() * 100).toInt()
            canvas.drawText("${cw}% × ${ch}%",
                cropRect.left + 8, cropRect.top + 36, infoPaint)
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadius, handlePaint)
        canvas.drawCircle(x, y, handleRadius, handleBorderPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────

    // Fix: CropOverlayView overrides onTouchEvent but not performClick — add performClick
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode  = detectHandle(x, y)
                dragStart = PointF(x, y)
                cropStart = RectF(cropRect)
                return dragMode != DragMode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return false
                val dx = x - dragStart.x
                val dy = y - dragStart.y
                applyDrag(dx, dy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.action == MotionEvent.ACTION_UP) performClick()
                dragMode = DragMode.NONE
                return true
            }
        }
        return false
    }

    private fun detectHandle(x: Float, y: Float): DragMode {
        fun near(hx: Float, hy: Float) =
            hypot((x - hx).toDouble(), (y - hy).toDouble()) < handleRadius * 1.5

        return when {
            near(cropRect.left,      cropRect.top)      -> DragMode.TOP_LEFT
            near(cropRect.right,     cropRect.top)      -> DragMode.TOP_RIGHT
            near(cropRect.left,      cropRect.bottom)   -> DragMode.BOTTOM_LEFT
            near(cropRect.right,     cropRect.bottom)   -> DragMode.BOTTOM_RIGHT
            near(cropRect.centerX(), cropRect.top)      -> DragMode.TOP
            near(cropRect.centerX(), cropRect.bottom)   -> DragMode.BOTTOM
            near(cropRect.left,      cropRect.centerY()) -> DragMode.LEFT
            near(cropRect.right,     cropRect.centerY()) -> DragMode.RIGHT
            cropRect.contains(x, y)                     -> DragMode.MOVE
            else                                        -> DragMode.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val iL = imageRect.left
        val iT = imageRect.top
        val iR = imageRect.right
        val iB = imageRect.bottom

        when (dragMode) {
            DragMode.MOVE -> {
                val newL = (cropStart.left + dx).coerceIn(iL, iR - cropStart.width())
                val newT = (cropStart.top  + dy).coerceIn(iT, iB - cropStart.height())
                cropRect.set(newL, newT, newL + cropStart.width(), newT + cropStart.height())
            }
            DragMode.TOP_LEFT -> {
                cropRect.left = (cropStart.left + dx).coerceIn(iL, cropRect.right  - minSize)
                cropRect.top  = (cropStart.top  + dy).coerceIn(iT, cropRect.bottom - minSize)
            }
            DragMode.TOP_RIGHT -> {
                cropRect.right = (cropStart.right + dx).coerceIn(cropRect.left + minSize, iR)
                cropRect.top   = (cropStart.top   + dy).coerceIn(iT, cropRect.bottom - minSize)
            }
            DragMode.BOTTOM_LEFT -> {
                cropRect.left   = (cropStart.left   + dx).coerceIn(iL, cropRect.right - minSize)
                cropRect.bottom = (cropStart.bottom + dy).coerceIn(cropRect.top + minSize, iB)
            }
            DragMode.BOTTOM_RIGHT -> {
                cropRect.right  = (cropStart.right  + dx).coerceIn(cropRect.left + minSize, iR)
                cropRect.bottom = (cropStart.bottom + dy).coerceIn(cropRect.top  + minSize, iB)
            }
            DragMode.TOP    -> cropRect.top    = (cropStart.top    + dy).coerceIn(iT, cropRect.bottom - minSize)
            DragMode.BOTTOM -> cropRect.bottom = (cropStart.bottom + dy).coerceIn(cropRect.top + minSize, iB)
            DragMode.LEFT   -> cropRect.left   = (cropStart.left   + dx).coerceIn(iL, cropRect.right  - minSize)
            DragMode.RIGHT  -> cropRect.right  = (cropStart.right  + dx).coerceIn(cropRect.left + minSize, iR)
            DragMode.NONE   -> {}
        }
    }
}