package zaujaani.roadsensecollector

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import zaujaani.roadsensecollector.databinding.ActivityVideoExtractorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VideoFrame menyimpan path ke file temp, bukan bitmap langsung di memory.
 * Bitmap di-load on-demand saat ditampilkan, lalu di-recycle setelah selesai.
 * Ini mencegah OOM pada video panjang.
 */
data class VideoFrame(
    val thumbPath : String,   // path file JPEG temp di cacheDir
    val timeMs    : Long,
    val isBlurry  : Boolean,
    var selected  : Boolean = false
) {
    fun loadBitmap(): Bitmap? = try {
        BitmapFactory.decodeFile(thumbPath)
    } catch (_: Exception) { null }
}

class VideoExtractorActivity : AppCompatActivity() {

    private lateinit var binding        : ActivityVideoExtractorBinding
    private val frames                  = mutableListOf<VideoFrame>()
    private lateinit var adapter        : VideoFrameAdapter
    private var videoUri                : Uri? = null
    private var intervalSeconds         = 2
    private var extractJob              : Job? = null
    private var wakeLock                : PowerManager.WakeLock? = null

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            videoUri = it
            loadVideoInfo(it)
            binding.btnExtract.isEnabled = true
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.any { it })
            videoPickerLauncher.launch("video/*")
        else
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_SHORT).show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoExtractorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        setupAdapter()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        // Hapus semua file temp saat activity destroy
        clearTempFrames()
    }

    // ── WakeLock helpers ──────────────────────────────────────────────
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "roadsensecollector:ExtractWakeLock"
        ).also {
            it.acquire(30 * 60 * 1000L) // max 30 menit
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun clearTempFrames() {
        frames.forEach { frame ->
            try { File(frame.thumbPath).delete() } catch (_: Exception) {}
        }
    }

    // ── UI Setup ──────────────────────────────────────────────────────
    private fun setupUI() {
        binding.btnBack.setOnClickListener           { finish() }
        binding.btnPickVideo.setOnClickListener      { pickVideo() }
        binding.btnExtract.setOnClickListener        { startExtract() }
        binding.btnSelectAll.setOnClickListener      { selectAll() }
        binding.btnClearSelection.setOnClickListener { clearSelection() }
        binding.btnAssignFrames.setOnClickListener   { showAssignDialog() }

        binding.seekInterval.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                intervalSeconds = (progress + 1)
                binding.tvInterval.text = getString(R.string.interval_value, intervalSeconds)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ── Pick video ────────────────────────────────────────────────────
    private fun pickVideo() {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        if (perms.any {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) videoPickerLauncher.launch("video/*")
        else permissionLauncher.launch(perms)
    }

    // ── Load video info ───────────────────────────────────────────────
    private fun loadVideoInfo(uri: Uri) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            val width    = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height   = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            retriever.release()

            val durationStr     = String.format(Locale.getDefault(), "%02d:%02d",
                duration / 60000, (duration % 60000) / 1000)
            val estimatedFrames = (duration / 1000 / intervalSeconds).toInt()
            binding.tvVideoInfo.text =
                getString(R.string.video_info_format, durationStr, width, height, estimatedFrames, intervalSeconds)
        } catch (_: Exception) {
            binding.tvVideoInfo.text = getString(R.string.video_info_default)
        }
    }

    // ── Extract frames ────────────────────────────────────────────────
    private fun startExtract() {
        val uri      = videoUri ?: return
        val prevSize = frames.size
        clearTempFrames()
        frames.clear()
        adapter.notifyItemRangeRemoved(0, prevSize)

        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility  = View.VISIBLE
        binding.btnExtract.isEnabled   = false
        binding.btnPickVideo.isEnabled = false

        acquireWakeLock()  // Cegah layar mati matikan proses

        extractJob = lifecycleScope.launch {
            val extracted = withContext(Dispatchers.IO) { extractFrames(uri) }

            // Hanya update UI jika job belum di-cancel
            if (isActive) {
                frames.addAll(extracted)
                adapter.notifyItemRangeInserted(0, extracted.size)
                updateFrameCount()

                binding.progressBar.visibility = View.GONE
                binding.tvProgress.visibility  = View.GONE
                binding.btnExtract.isEnabled   = true
                binding.btnPickVideo.isEnabled = true

                Toast.makeText(this@VideoExtractorActivity,
                    getString(R.string.extract_success, frames.size),
                    Toast.LENGTH_LONG).show()
            }
            releaseWakeLock()
        }
    }

    private suspend fun extractFrames(uri: Uri): List<VideoFrame> {
        val result    = mutableListOf<VideoFrame>()
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(this, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: return result
            val intervalMs = intervalSeconds * 1000L
            var currentMs  = 0L
            var frameIndex = 0

            while (currentMs <= durationMs) {
                // Cek coroutine masih aktif — stop jika di-cancel
                if (!extractJob!!.isActive) break

                val bitmap = retriever.getFrameAtTime(
                    currentMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

                if (bitmap != null) {
                    // Simpan ke file temp, JANGAN simpan bitmap ke list
                    val thumbFile = File(cacheDir, "thumb_${frameIndex}_${currentMs}.jpg")
                    val saved = try {
                        thumbFile.outputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        true
                    } catch (_: Exception) { false }

                    if (saved) {
                        val isBlurry = detectBlur(bitmap)
                        result.add(VideoFrame(thumbFile.absolutePath, currentMs, isBlurry))
                    }

                    // Recycle bitmap segera setelah dipakai
                    bitmap.recycle()
                    frameIndex++

                    withContext(Dispatchers.Main) {
                        val progress = ((currentMs.toFloat() / durationMs) * 100).toInt()
                        binding.progressBar.progress = progress
                        binding.tvProgress.text =
                            getString(R.string.extract_progress, frameIndex, progress)
                    }
                }
                currentMs += intervalMs
            }
            result
        } catch (_: Exception) {
            result
        } finally {
            retriever.release()
        }
    }

    // ── Blur detection ────────────────────────────────────────────────
    private fun detectBlur(bitmap: Bitmap): Boolean {
        return try {
            val width  = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val cx    = width  / 2
            val cy    = height / 2
            val range = 50
            var sum   = 0.0
            var sumSq = 0.0
            var count = 0

            for (y in (cy - range)..(cy + range)) {
                for (x in (cx - range)..(cx + range)) {
                    if (x in 0 until width && y in 0 until height) {
                        val pixel = pixels[y * width + x]
                        val gray  = (0.299 * ((pixel shr 16) and 0xFF) +
                                0.587 * ((pixel shr 8)  and 0xFF) +
                                0.114 * (pixel and 0xFF))
                        sum   += gray
                        sumSq += gray * gray
                        count++
                    }
                }
            }
            if (count == 0) return false
            val mean     = sum / count
            val variance = (sumSq / count) - (mean * mean)
            variance < 100.0
        } catch (_: Exception) { false }
    }

    // ── Adapter ───────────────────────────────────────────────────────
    private fun setupAdapter() {
        adapter = VideoFrameAdapter(
            frames      = frames,
            onPreview   = { position -> openFramePreview(position) },
            onLongPress = { position ->
                frames[position].selected = !frames[position].selected
                adapter.notifyItemChanged(position)
                updateFrameCount()
            }
        )
        binding.rvFrames.layoutManager = GridLayoutManager(this, 3)
        binding.rvFrames.adapter       = adapter
    }

    private fun updateFrameCount() {
        val selected = frames.count { it.selected }
        binding.tvFrameCount.text         = getString(R.string.frame_count_format, selected, frames.size)
        binding.btnAssignFrames.isEnabled = selected > 0
        binding.btnAssignFrames.alpha     = if (selected > 0) 1f else 0.5f
    }

    private fun selectAll() {
        frames.forEach { it.selected = true }
        adapter.notifyItemRangeChanged(0, frames.size)
        updateFrameCount()
    }

    private fun clearSelection() {
        frames.forEach { it.selected = false }
        adapter.notifyItemRangeChanged(0, frames.size)
        updateFrameCount()
    }

    // ── Fullscreen Frame Preview ──────────────────────────────────────
    private fun openFramePreview(startPosition: Int) {
        if (frames.isEmpty()) return

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Inflate dulu SEBELUM show() agar window siap
        val view = LayoutInflater.from(this)
            .inflate(R.layout.dialog_frame_preview, null)
        dialog.setContentView(view)

        val win = dialog.window
        win?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        // show() dulu, BARU akses insetsController (decorView sudah attach)
        dialog.show()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            win?.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            win?.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        var currentPos = startPosition

        val imgPreview      = view.findViewById<ImageView>(R.id.imgPreviewFull)
        val tvTimestamp     = view.findViewById<TextView>(R.id.tvPreviewTimestamp)
        val tvBlur          = view.findViewById<TextView>(R.id.tvPreviewBlur)
        val tvIndex         = view.findViewById<TextView>(R.id.tvPreviewIndex)
        val btnClose        = view.findViewById<View>(R.id.btnClosePreview)
        val btnPrev         = view.findViewById<View>(R.id.btnPrevFrame)
        val btnNext         = view.findViewById<View>(R.id.btnNextFrame)
        val btnToggleSelect = view.findViewById<TextView>(R.id.btnToggleSelect)
        val btnAssignDirect = view.findViewById<View>(R.id.btnAssignDirect)

        // ── Pinch-to-zoom + pan ───────────────────────────────────────
        val matrix      = Matrix()
        var scaleFactor = 1f
        var lastX       = 0f
        var lastY       = 0f
        var currentBitmap: Bitmap? = null

        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1f, 8f)
                    matrix.setScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                    imgPreview.imageMatrix = matrix
                    return true
                }
            })

        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    scaleFactor = 1f
                    matrix.reset()
                    imgPreview.imageMatrix = matrix
                    return true
                }
            })

        imgPreview.scaleType = ImageView.ScaleType.MATRIX

        imgPreview.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleFactor > 1f) {
                        matrix.postTranslate(event.x - lastX, event.y - lastY)
                        imgPreview.imageMatrix = matrix
                        lastX = event.x
                        lastY = event.y
                    }
                }
            }
            imgPreview.performClick()
            true
        }

        // ── Render frame ──────────────────────────────────────────────
        fun renderFrame(pos: Int) {
            val frame = frames[pos]

            // Recycle bitmap lama sebelum load baru
            currentBitmap?.recycle()
            currentBitmap = frame.loadBitmap()
            val bmp = currentBitmap ?: return

            scaleFactor = 1f
            matrix.reset()
            imgPreview.imageMatrix = matrix
            imgPreview.scaleType   = ImageView.ScaleType.FIT_CENTER
            imgPreview.setImageBitmap(bmp)
            imgPreview.scaleType   = ImageView.ScaleType.MATRIX
            imgPreview.post {
                val bw = bmp.width.toFloat()
                val bh = bmp.height.toFloat()
                val vw = imgPreview.width.toFloat()
                val vh = imgPreview.height.toFloat()
                if (vw > 0 && vh > 0) {
                    val scale = minOf(vw / bw, vh / bh)
                    matrix.reset()
                    matrix.setScale(scale, scale)
                    matrix.postTranslate((vw - bw * scale) / 2f, (vh - bh * scale) / 2f)
                    imgPreview.imageMatrix = matrix
                }
            }

            val seconds = frame.timeMs / 1000
            tvTimestamp.text  = getString(R.string.preview_timestamp_format, seconds / 60, seconds % 60)
            tvBlur.visibility = if (frame.isBlurry) View.VISIBLE else View.GONE
            tvIndex.text      = getString(R.string.preview_index_format, pos + 1, frames.size)

            val isSelected = frame.selected
            btnToggleSelect.text = getString(
                if (isSelected) R.string.btn_frame_selected else R.string.btn_frame_select
            )
            (btnToggleSelect as? android.widget.Button)?.backgroundTintList =
                ColorStateList.valueOf(
                    ContextCompat.getColor(this,
                        if (isSelected) R.color.select_active else R.color.select_inactive)
                )
        }

        // Recycle bitmap saat dialog ditutup
        dialog.setOnDismissListener {
            currentBitmap?.recycle()
            currentBitmap = null
        }

        renderFrame(currentPos)

        // ── Navigation ────────────────────────────────────────────────
        btnPrev.setOnClickListener {
            if (currentPos > 0) { currentPos--; renderFrame(currentPos) }
        }
        btnNext.setOnClickListener {
            if (currentPos < frames.size - 1) { currentPos++; renderFrame(currentPos) }
        }
        btnToggleSelect.setOnClickListener {
            frames[currentPos].selected = !frames[currentPos].selected
            adapter.notifyItemChanged(currentPos)
            updateFrameCount()
            renderFrame(currentPos)
        }
        btnAssignDirect.setOnClickListener {
            dialog.dismiss()
            if (frames.none { it.selected }) {
                frames[currentPos].selected = true
                adapter.notifyItemChanged(currentPos)
                updateFrameCount()
            }
            showAssignDialog()
        }
        btnClose.setOnClickListener { dialog.dismiss() }
    }

    // ── Assign ke class ───────────────────────────────────────────────
    private fun showAssignDialog() {
        val selected = frames.filter { it.selected }
        if (selected.isEmpty()) return

        val classList = RoadClasses.ALL_CLASSES
        val names     = classList.map {
            getString(R.string.class_name_format, it.code, it.nameId)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.assign_dialog_title, selected.size))
            .setItems(names) { _, which ->
                val cls = classList[which]
                lifecycleScope.launch {
                    var saved = 0
                    withContext(Dispatchers.IO) {
                        selected.forEach { frame ->
                            val bmp = frame.loadBitmap()
                            if (bmp != null) {
                                if (saveFrameToClass(bmp, cls)) saved++
                                bmp.recycle()
                            }
                        }
                    }
                    CollectorStats.recalculateFromDisk(this@VideoExtractorActivity)
                    clearSelection()
                    Toast.makeText(
                        this@VideoExtractorActivity,
                        getString(R.string.assign_success, saved, cls.code, cls.nameId),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ── Save frame → standardize → class folder ───────────────────────
    private fun saveFrameToClass(bitmap: Bitmap, cls: RoadClasses.RoadClass): Boolean {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val filename  = "${cls.code}_${timestamp}.jpg"

            val tempFile = File(cacheDir, "frame_$filename")
            tempFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            val tempStd = File(cacheDir, "std_$filename")
            val success = ImageStandardizer.standardizeFile(tempFile, tempStd)
            tempFile.delete()
            if (!success) return false

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/RoadSenseCollector/${cls.code}_${cls.nameId}")
            }

            val destUri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            destUri?.let { dest ->
                contentResolver.openOutputStream(dest)?.use { output ->
                    tempStd.inputStream().use { input -> input.copyTo(output) }
                }
                tempStd.delete()
                CollectorStats.saveGpsJson(
                    context       = this,
                    classCode     = cls.code,
                    photoFilename = filename,
                    location      = CollectorStats.getLastLocation(this)
                )
                CollectorStats.increment(this, cls.code)
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
}

// ── VideoFrameAdapter ─────────────────────────────────────────────────
class VideoFrameAdapter(
    private val frames     : List<VideoFrame>,
    private val onPreview  : (Int) -> Unit,
    private val onLongPress: (Int) -> Unit
) : RecyclerView.Adapter<VideoFrameAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val img            : ImageView = view.findViewById(R.id.imgFrame)
        val overlaySelected: View      = view.findViewById(R.id.overlaySelected)
        val tvCheck        : TextView  = view.findViewById(R.id.tvCheck)
        val tvTimestamp    : TextView  = view.findViewById(R.id.tvTimestamp)
        val tvBlur         : TextView  = view.findViewById(R.id.tvBlur)
        val tvTapHint      : TextView  = view.findViewById(R.id.tvTapHint)
        var thumbBitmap    : Bitmap?   = null  // track untuk recycle
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_frame, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val frame      = frames[position]
        val isSelected = frame.selected

        // Recycle bitmap lama sebelum load baru
        holder.thumbBitmap?.recycle()
        holder.thumbBitmap = frame.loadBitmap()
        holder.img.setImageBitmap(holder.thumbBitmap)

        val seconds = frame.timeMs / 1000
        holder.tvTimestamp.text = holder.itemView.context.getString(
            R.string.timestamp_format, seconds / 60, seconds % 60)

        holder.tvBlur.visibility          = if (frame.isBlurry) View.VISIBLE else View.GONE
        holder.overlaySelected.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.tvCheck.visibility         = if (isSelected) View.VISIBLE else View.GONE
        holder.tvTapHint.visibility       = if (isSelected) View.GONE    else View.VISIBLE

        holder.itemView.setOnClickListener { onPreview(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongPress(holder.bindingAdapterPosition)
            true
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.thumbBitmap?.recycle()
        holder.thumbBitmap = null
        holder.img.setImageDrawable(null)
    }

    override fun getItemCount() = frames.size
}