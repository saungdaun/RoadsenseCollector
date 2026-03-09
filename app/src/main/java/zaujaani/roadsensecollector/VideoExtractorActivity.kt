package zaujaani.roadsensecollector

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class VideoFrame(
    val bitmap   : Bitmap,
    val timeMs   : Long,
    val isBlurry : Boolean,
    var selected : Boolean = false
)

class VideoExtractorActivity : AppCompatActivity() {

    private lateinit var binding  : ActivityVideoExtractorBinding
    private val frames            = mutableListOf<VideoFrame>()
    private lateinit var adapter  : VideoFrameAdapter
    private var videoUri          : Uri? = null
    private var intervalSeconds   = 2

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
            Toast.makeText(this, "Izin storage diperlukan!", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoExtractorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupAdapter()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener          { finish() }
        binding.btnPickVideo.setOnClickListener     { pickVideo() }
        binding.btnExtract.setOnClickListener       { startExtract() }
        binding.btnSelectAll.setOnClickListener     { selectAll() }
        binding.btnClearSelection.setOnClickListener{ clearSelection() }
        binding.btnAssignFrames.setOnClickListener  { showAssignDialog() }

        // SeekBar interval
        binding.seekInterval.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                intervalSeconds = (progress + 1)
                binding.tvInterval.text = "Setiap $intervalSeconds detik"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ── Pick video ────────────────────────────────────────────────────
    private fun pickVideo() {
        val perms = if (android.os.Build.VERSION.SDK_INT >= 33)
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

            val durationStr = String.format("%02d:%02d",
                duration / 60000, (duration % 60000) / 1000)
            val estimatedFrames = (duration / 1000 / intervalSeconds).toInt()

            binding.tvVideoInfo.text =
                "⏱ $durationStr  |  📐 ${width}×${height}  |  ~$estimatedFrames frame @ ${intervalSeconds}s"

        } catch (e: Exception) {
            binding.tvVideoInfo.text = "Video dipilih — siap extract"
        }
    }

    // ── Extract frames ────────────────────────────────────────────────
    private fun startExtract() {
        val uri = videoUri ?: return
        frames.clear()
        adapter.notifyDataSetChanged()

        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility  = View.VISIBLE
        binding.btnExtract.isEnabled   = false
        binding.btnPickVideo.isEnabled = false

        lifecycleScope.launch {
            val extracted = withContext(Dispatchers.IO) {
                extractFrames(uri)
            }
            frames.addAll(extracted)
            adapter.notifyDataSetChanged()
            updateFrameCount()

            binding.progressBar.visibility = View.GONE
            binding.tvProgress.visibility  = View.GONE
            binding.btnExtract.isEnabled   = true
            binding.btnPickVideo.isEnabled = true

            Toast.makeText(this@VideoExtractorActivity,
                "✅ ${frames.size} frame diekstrak (blur otomatis ditandai)",
                Toast.LENGTH_SHORT).show()
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
                val bitmap = retriever.getFrameAtTime(
                    currentMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

                if (bitmap != null) {
                    val isBlurry = detectBlur(bitmap)
                    result.add(VideoFrame(bitmap, currentMs, isBlurry))
                    frameIndex++

                    withContext(Dispatchers.Main) {
                        val progress = ((currentMs.toFloat() / durationMs) * 100).toInt()
                        binding.progressBar.progress = progress
                        binding.tvProgress.text =
                            "Mengekstrak frame $frameIndex... ($progress%)"
                    }
                }
                currentMs += intervalMs
            }
            result
        } catch (e: Exception) {
            result
        } finally {
            retriever.release()
        }
    }

    // ── Blur detection pakai Laplacian variance ───────────────────────
    private fun detectBlur(bitmap: Bitmap): Boolean {
        return try {
            val width  = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // Sample tengah 100x100 pixel saja (cepat)
            val cx     = width  / 2
            val cy     = height / 2
            val range  = 50
            var sum    = 0.0
            var sumSq  = 0.0
            var count  = 0

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

            // Variance < 100 = blur
            variance < 100.0
        } catch (e: Exception) { false }
    }

    // ── Adapter ───────────────────────────────────────────────────────
    private fun setupAdapter() {
        adapter = VideoFrameAdapter(frames) { position ->
            frames[position].selected = !frames[position].selected
            adapter.notifyItemChanged(position)
            updateFrameCount()
        }
        binding.rvFrames.layoutManager = GridLayoutManager(this, 3)
        binding.rvFrames.adapter       = adapter
    }

    private fun updateFrameCount() {
        val selected = frames.count { it.selected }
        binding.tvFrameCount.text          = "$selected / ${frames.size} dipilih"
        binding.btnAssignFrames.isEnabled  = selected > 0
        binding.btnAssignFrames.alpha      = if (selected > 0) 1f else 0.5f
    }

    private fun selectAll() {
        frames.forEach { it.selected = true }
        adapter.notifyDataSetChanged()
        updateFrameCount()
    }

    private fun clearSelection() {
        frames.forEach { it.selected = false }
        adapter.notifyDataSetChanged()
        updateFrameCount()
    }

    // ── Assign ke class ───────────────────────────────────────────────
    private fun showAssignDialog() {
        val selected = frames.filter { it.selected }
        if (selected.isEmpty()) return

        val classList = RoadClasses.ALL_CLASSES
        val names     = classList.map { "${it.code} — ${it.nameId}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Assign ${selected.size} frame ke class:")
            .setItems(names) { _, which ->
                val cls = classList[which]
                lifecycleScope.launch {
                    var saved = 0
                    withContext(Dispatchers.IO) {
                        selected.forEach { frame ->
                            if (saveFrameToClass(frame.bitmap, cls)) saved++
                        }
                    }
                    CollectorStats.recalculateFromDisk(this@VideoExtractorActivity)
                    clearSelection()
                    Toast.makeText(this@VideoExtractorActivity,
                        "✅ $saved frame disimpan ke ${cls.code} — ${cls.nameId}",
                        Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ── Save frame → standardize → class folder ───────────────────────
    private fun saveFrameToClass(bitmap: Bitmap, cls: RoadClasses.RoadClass): Boolean {
        return try {
            val timestamp   = SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
                Locale.getDefault()).format(Date())
            val filename    = "${cls.code}_${timestamp}.jpg"

            // Simpan bitmap ke temp file dulu
            val tempFile = File(cacheDir, "frame_$filename")
            tempFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            // Standardize → 640×640
            val tempStd = File(cacheDir, "std_$filename")
            val success = ImageStandardizer.standardizeFile(tempFile, tempStd)
            tempFile.delete()
            if (!success) return false

            // Simpan ke MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/RoadSenseCollector/" +
                            "${cls.code}_${cls.nameId}")
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
        } catch (e: Exception) {
            false
        }
    }
}

// ── VideoFrameAdapter ─────────────────────────────────────────────────
class VideoFrameAdapter(
    private val frames  : List<VideoFrame>,
    private val onToggle: (Int) -> Unit
) : RecyclerView.Adapter<VideoFrameAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val img            : ImageView = view.findViewById(R.id.imgFrame)
        val overlaySelected: View      = view.findViewById(R.id.overlaySelected)
        val tvCheck        : TextView  = view.findViewById(R.id.tvCheck)
        val tvTimestamp    : TextView  = view.findViewById(R.id.tvTimestamp)
        val tvBlur         : TextView  = view.findViewById(R.id.tvBlur)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_frame, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val frame = frames[position]

        holder.img.setImageBitmap(frame.bitmap)

        // Timestamp
        val seconds = frame.timeMs / 1000
        holder.tvTimestamp.text = String.format("%02d:%02d", seconds / 60, seconds % 60)

        // Blur warning
        holder.tvBlur.visibility =
            if (frame.isBlurry) View.VISIBLE else View.GONE

        // Selection state
        holder.overlaySelected.visibility =
            if (frame.selected) View.VISIBLE else View.GONE
        holder.tvCheck.visibility =
            if (frame.selected) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener     { onToggle(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener { onToggle(holder.bindingAdapterPosition); true }
    }

    override fun getItemCount() = frames.size
}