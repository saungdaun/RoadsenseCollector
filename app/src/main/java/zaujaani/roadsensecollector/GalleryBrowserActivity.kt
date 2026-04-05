package zaujaani.roadsensecollector

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import zaujaani.roadsensecollector.databinding.ActivityGalleryBrowserBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class GalleryPhoto(
    val uri      : Uri,
    val filename : String,
    val dateTaken: Long,
    var selected : Boolean = false
)

class GalleryBrowserActivity : AppCompatActivity() {

    private lateinit var binding : ActivityGalleryBrowserBinding
    private val photos           = mutableListOf<GalleryPhoto>()
    private lateinit var adapter : GalleryAdapter
    private var currentFolder    = "Semua"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.any { it }) loadGallery()
        else Toast.makeText(this, "Izin storage diperlukan!", Toast.LENGTH_SHORT).show()
    }

    // Crop launcher — menerima URI hasil crop dari CropActivity
    private var pendingCropUri: Uri? = null

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val cropPath = result.data?.getStringExtra(CropActivity.EXTRA_CROP_PATH)
            if (cropPath != null) {
                showAssignDialogForUri(Uri.fromFile(File(cropPath)), isCropResult = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener            { finish() }
        binding.btnAssign.setOnClickListener          { showAssignDialog() }
        binding.btnSelectAll.setOnClickListener       { selectAll() }
        binding.btnClearSelection.setOnClickListener  { clearSelection() }
        binding.btnCropSelected.setOnClickListener    { cropFirstSelected() }

        setupAdapter()
        checkPermissions()
    }

    // ── Permission ────────────────────────────────────────────────────
    private fun checkPermissions() {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (perms.any {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) loadGallery()
        else permissionLauncher.launch(perms)
    }

    // ── Load gallery ──────────────────────────────────────────────────
    private fun loadGallery(folderFilter: String = "Semua") {
        photos.clear()
        currentFolder = folderFilter

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val cursor: Cursor? = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )

        val folders = mutableSetOf("Semua")
        cursor?.use {
            val idCol     = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol   = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol   = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val bucketCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (it.moveToNext()) {
                val id     = it.getLong(idCol)
                val name   = it.getString(nameCol)   ?: ""
                val date   = it.getLong(dateCol)
                val bucket = it.getString(bucketCol) ?: "Lainnya"
                folders.add(bucket)
                if (folderFilter == "Semua" || folderFilter == bucket) {
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                    )
                    photos.add(GalleryPhoto(uri, name, date))
                }
            }
        }

        adapter.notifyDataSetChanged()
        updateSelectedCount()
        setupFolderFilter(folders.toList())
        binding.tvTitle.text =
            "🖼️ ${if (folderFilter == "Semua") "Semua Foto" else folderFilter} (${photos.size})"
    }

    // ── Folder filter ─────────────────────────────────────────────────
    private fun setupFolderFilter(folders: List<String>) {
        binding.filterContainer.removeAllViews()
        folders.sorted().forEach { folder ->
            val btn = Button(this).apply {
                text     = folder
                textSize = 11f
                setPadding(24, 8, 24, 8)
                setBackgroundColor(
                    if (folder == currentFolder) getColor(R.color.primary)
                    else getColor(R.color.bg_item)
                )
                setTextColor(getColor(R.color.white))
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 8 }
                setOnClickListener { loadGallery(folder) }
            }
            binding.filterContainer.addView(btn)
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────
    private fun setupAdapter() {
        adapter = GalleryAdapter(
            photos      = photos,
            onTap       = { position -> openZoomPreview(position) },
            onLongPress = { position ->
                photos[position].selected = !photos[position].selected
                adapter.notifyItemChanged(position)
                updateSelectedCount()
            }
        )
        binding.rvGallery.layoutManager = GridLayoutManager(this, 3)
        binding.rvGallery.adapter       = adapter
    }

    private fun updateSelectedCount() {
        val count = photos.count { it.selected }
        binding.tvSelectedCount.text = "$count dipilih"

        val hasSelection = count > 0
        binding.btnAssign.isEnabled      = hasSelection
        binding.btnAssign.alpha          = if (hasSelection) 1f else 0.5f
        binding.btnCropSelected.isEnabled = count == 1   // Crop hanya 1 foto
        binding.btnCropSelected.alpha    = if (count == 1) 1f else 0.5f
    }

    private fun selectAll() {
        photos.forEach { it.selected = true }
        adapter.notifyDataSetChanged()
        updateSelectedCount()
    }

    private fun clearSelection() {
        photos.forEach { it.selected = false }
        adapter.notifyDataSetChanged()
        updateSelectedCount()
    }

    // ── Crop selected photo (hanya 1) ─────────────────────────────────
    private fun cropFirstSelected() {
        val selected = photos.firstOrNull { it.selected } ?: return
        val intent = Intent(this, CropActivity::class.java).apply {
            putExtra(CropActivity.EXTRA_SOURCE_URI, selected.uri.toString())
        }
        cropLauncher.launch(intent)
    }

    // ── Zoom Preview ──────────────────────────────────────────────────
    private fun openZoomPreview(startPosition: Int) {
        if (photos.isEmpty()) return

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC1A1A1A.toInt())
            setPadding(8, 8, 8, 8)
        }

        val btnClosePreview = Button(this).apply {
            text = "✕"
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(88, 88)
        }

        val tvPreviewInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        val tvZoomHint = TextView(this).apply {
            text = "Pinch·2× tap reset"
            textSize = 9f
            setTextColor(0xFF666666.toInt())
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 8, 0)
        }

        topBar.addView(btnClosePreview)
        topBar.addView(tvPreviewInfo)
        topBar.addView(tvZoomHint)

        val imgPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(0xFF050505.toInt())
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC1A1A1A.toInt())
            setPadding(8, 10, 8, 10)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val btnPrev = Button(this).apply {
            text = "◀"
            setBackgroundColor(0xFF2A2A2A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(88, 88)
        }

        val btnToggleSelect = Button(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, 88, 1.2f).also {
                it.marginStart = 8; it.marginEnd = 4
            }
        }

        val btnCropThis = Button(this).apply {
            text = "✂ Crop"
            setBackgroundColor(0xFF4A148C.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, 88, 0.9f).also { it.marginEnd = 4 }
        }

        val btnAssignDirect = Button(this).apply {
            text = "📁 Assign"
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, 88, 1.2f).also { it.marginEnd = 8 }
        }

        val btnNext = Button(this).apply {
            text = "▶"
            setBackgroundColor(0xFF2A2A2A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(88, 88)
        }

        bottomBar.addView(btnPrev)
        bottomBar.addView(btnToggleSelect)
        bottomBar.addView(btnCropThis)
        bottomBar.addView(btnAssignDirect)
        bottomBar.addView(btnNext)

        root.addView(topBar)
        root.addView(imgPreview)
        root.addView(bottomBar)

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insetsController?.hide(WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
        dialog.show()

        var currentPos  = startPosition
        val matrix      = Matrix()
        var scaleFactor = 1f
        var lastX       = 0f
        var lastY       = 0f

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
                    scaleFactor = 1f; matrix.reset(); imgPreview.imageMatrix = matrix; return true
                }
            })

        imgPreview.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleFactor > 1f) {
                        matrix.postTranslate(event.x - lastX, event.y - lastY)
                        imgPreview.imageMatrix = matrix
                        lastX = event.x; lastY = event.y
                    }
                }
            }
            imgPreview.performClick()
            true
        }

        fun renderPhoto(pos: Int) {
            val photo = photos[pos]
            scaleFactor = 1f; matrix.reset()
            imgPreview.scaleType = ImageView.ScaleType.FIT_CENTER
            Glide.with(this).load(photo.uri).into(imgPreview)
            imgPreview.scaleType = ImageView.ScaleType.MATRIX
            imgPreview.post {
                imgPreview.drawable?.let { d ->
                    val bw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
                    val bh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
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
            }
            val dateStr = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
                .format(Date(photo.dateTaken))
            tvPreviewInfo.text = "${pos + 1}/${photos.size}  •  $dateStr"
            val isSelected = photo.selected
            btnToggleSelect.text = if (isSelected) "✓ Dipilih" else "○ Pilih"
            btnToggleSelect.setBackgroundColor(
                if (isSelected) 0xFF2E7D32.toInt() else 0xFF4A4A4A.toInt()
            )
            btnPrev.alpha = if (pos > 0) 1f else 0.3f
            btnNext.alpha = if (pos < photos.size - 1) 1f else 0.3f
        }

        renderPhoto(currentPos)

        btnPrev.setOnClickListener {
            if (currentPos > 0) { currentPos--; renderPhoto(currentPos) }
        }
        btnNext.setOnClickListener {
            if (currentPos < photos.size - 1) { currentPos++; renderPhoto(currentPos) }
        }
        btnToggleSelect.setOnClickListener {
            photos[currentPos].selected = !photos[currentPos].selected
            adapter.notifyItemChanged(currentPos)
            updateSelectedCount()
            renderPhoto(currentPos)
        }
        btnCropThis.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, CropActivity::class.java).apply {
                putExtra(CropActivity.EXTRA_SOURCE_URI, photos[currentPos].uri.toString())
            }
            cropLauncher.launch(intent)
        }
        btnAssignDirect.setOnClickListener {
            val classList = RoadClasses.getActiveClasses(this)
            val names     = classList.map { "${it.code} — ${it.nameId}" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Assign foto ke class:")
                .setItems(names) { _, which ->
                    val cls   = classList[which]
                    val saved = copyToClass(photos[currentPos].uri, cls)
                    CollectorStats.recalculateFromDisk(this)
                    if (saved) {
                        Toast.makeText(this, "✅ Disimpan ke ${cls.code} — ${cls.nameId}",
                            Toast.LENGTH_SHORT).show()
                        if (currentPos < photos.size - 1) {
                            currentPos++; renderPhoto(currentPos)
                        } else dialog.dismiss()
                    } else {
                        Toast.makeText(this, "Gagal menyimpan foto", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        btnClosePreview.setOnClickListener { dialog.dismiss() }
    }

    // ── Assign batch ──────────────────────────────────────────────────
    private fun showAssignDialog() {
        val selected  = photos.filter { it.selected }
        if (selected.isEmpty()) return
        val classList = RoadClasses.getActiveClasses(this)
        val names     = classList.map { "${it.code} — ${it.nameId}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Assign ${selected.size} foto ke class:")
            .setItems(names) { _, which ->
                val cls   = classList[which]
                var saved = 0
                selected.forEach { photo -> if (copyToClass(photo.uri, cls)) saved++ }
                CollectorStats.recalculateFromDisk(this)
                clearSelection()
                Toast.makeText(this,
                    "✅ $saved foto disimpan ke ${cls.code} — ${cls.nameId}",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ── Assign setelah crop ───────────────────────────────────────────
    private fun showAssignDialogForUri(uri: Uri, isCropResult: Boolean = false) {
        val classList = RoadClasses.getActiveClasses(this)
        val names     = classList.map { "${it.code} — ${it.nameId}" }.toTypedArray()
        val title     = if (isCropResult) "Simpan hasil crop ke class:" else "Assign ke class:"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, which ->
                val cls   = classList[which]
                val saved = copyToClass(uri, cls)
                CollectorStats.recalculateFromDisk(this)
                if (saved) Toast.makeText(this,
                    "✅ Crop disimpan ke ${cls.code} — ${cls.nameId}",
                    Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, "Gagal menyimpan", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ── Copy + Standardize ────────────────────────────────────────────
    private fun copyToClass(sourceUri: Uri, cls: RoadClasses.RoadClass): Boolean {
        return try {
            val timestamp   = SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
                Locale.getDefault()).format(Date())
            val newFilename = "${cls.code}_${timestamp}.jpg"
            val tempStd     = File(cacheDir, "std_$newFilename")
            val success     = ImageStandardizer.standardize(this, sourceUri, tempStd)
            if (!success) return false

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, newFilename)
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
                    photoFilename = newFilename,
                    location      = CollectorStats.getLastLocation(this)
                )
                CollectorStats.increment(this, cls.code)
                true
            } ?: false
        } catch (_: Exception) { false }
    }
}

// ── GalleryAdapter ────────────────────────────────────────────────────
class GalleryAdapter(
    private val photos     : List<GalleryPhoto>,
    private val onTap      : (Int) -> Unit,
    private val onLongPress: (Int) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val img            : ImageView = view.findViewById(R.id.imgGallery)
        val overlaySelected: View      = view.findViewById(R.id.overlaySelected)
        val tvCheck        : TextView  = view.findViewById(R.id.tvCheck)
        val tvDate         : TextView  = view.findViewById(R.id.tvDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_photo, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val photo   = photos[position]
        val dateStr = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
            .format(Date(photo.dateTaken))
        Glide.with(holder.itemView.context)
            .load(photo.uri)
            .centerCrop()
            .into(holder.img)
        holder.tvDate.text                = dateStr
        holder.overlaySelected.visibility = if (photo.selected) View.VISIBLE else View.GONE
        holder.tvCheck.visibility         = if (photo.selected) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onTap(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener { onLongPress(holder.bindingAdapterPosition); true }
    }

    override fun getItemCount() = photos.size
}