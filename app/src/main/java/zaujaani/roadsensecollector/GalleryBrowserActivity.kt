package zaujaani.roadsensecollector

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener            { finish() }
        binding.btnAssign.setOnClickListener          { showAssignDialog() }
        binding.btnSelectAll.setOnClickListener       { selectAll() }
        binding.btnClearSelection.setOnClickListener  { clearSelection() }

        setupAdapter()
        checkPermissions()
    }

    // ── Permission ────────────────────────────────────────────────────
    private fun checkPermissions() {
        val perms = when {
            android.os.Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            android.os.Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES
            )
            else -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }

        if (perms.any {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            loadGallery()
        } else {
            permissionLauncher.launch(perms)
        }
    }

    // ── Load gallery dari MediaStore ──────────────────────────────────
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

    // ── Folder filter chips ───────────────────────────────────────────
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
        adapter = GalleryAdapter(photos) { position ->
            photos[position].selected = !photos[position].selected
            adapter.notifyItemChanged(position)
            updateSelectedCount()
        }
        binding.rvGallery.layoutManager = GridLayoutManager(this, 3)
        binding.rvGallery.adapter       = adapter
    }

    private fun updateSelectedCount() {
        val count               = photos.count { it.selected }
        binding.tvSelectedCount.text = "$count dipilih"
        binding.btnAssign.isEnabled  = count > 0
        binding.btnAssign.alpha      = if (count > 0) 1f else 0.5f
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

    // ── Assign ke class ───────────────────────────────────────────────
    private fun showAssignDialog() {
        val selected = photos.filter { it.selected }
        if (selected.isEmpty()) return

        val classList = RoadClasses.ALL_CLASSES
        val names     = classList.map { "${it.code} — ${it.nameId}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Assign ${selected.size} foto ke class:")
            .setItems(names) { _, which ->
                val cls   = classList[which]
                var saved = 0
                selected.forEach { photo ->
                    if (copyToClass(photo.uri, cls)) saved++
                }
                CollectorStats.recalculateFromDisk(this)
                clearSelection()
                Toast.makeText(this,
                    "✅ $saved foto disimpan ke ${cls.code} — ${cls.nameId}",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ── Copy + Standardize ke class ───────────────────────────────────
    private fun copyToClass(sourceUri: Uri, cls: RoadClasses.RoadClass): Boolean {
        return try {
            val timestamp   = SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
                Locale.getDefault()).format(Date())
            val newFilename = "${cls.code}_${timestamp}.jpg"

            // Standardize → 640×640, koreksi rotasi
            val tempStd = File(cacheDir, "std_$newFilename")
            val success = ImageStandardizer.standardize(this, sourceUri, tempStd)
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
        } catch (e: Exception) {
            false
        }
    }
}

// ── GalleryAdapter ────────────────────────────────────────────────────
class GalleryAdapter(
    private val photos  : List<GalleryPhoto>,
    private val onToggle: (Int) -> Unit
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

        holder.tvDate.text             = dateStr
        holder.overlaySelected.visibility =
            if (photo.selected) View.VISIBLE else View.GONE
        holder.tvCheck.visibility      =
            if (photo.selected) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener     { onToggle(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener { onToggle(holder.bindingAdapterPosition); true }
    }

    override fun getItemCount() = photos.size
}