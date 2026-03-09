package zaujaani.roadsensecollector

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import zaujaani.roadsensecollector.databinding.ActivityCollectorBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CollectorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectorBinding
    private var imageCapture: ImageCapture? = null
    private var lastPhotoUri: Uri? = null
    private var selectedClass: RoadClasses.RoadClass? = null
    private var lastLocation: Location? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraOk   = permissions[Manifest.permission.CAMERA] == true
        val locationOk = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (cameraOk) startCamera()
        else Toast.makeText(this, "Izin kamera diperlukan!", Toast.LENGTH_SHORT).show()
        if (locationOk) fetchLocation()
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) showClassPickerForImport(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClassGrid()

        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnImport.setOnClickListener  { galleryLauncher.launch("image/*") }
        binding.btnBack.setOnClickListener    { finish() }

        binding.tvTotalPhotos.text = "Total: ${CollectorStats.getTotal(this)} foto"

        checkPermissions()
    }

    // ── Permissions ───────────────────────────────────────────────────
    private fun checkPermissions() {
        val required = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (required.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            startCamera()
            fetchLocation()
        } else {
            permissionLauncher.launch(required)
        }
    }

    // ── GPS ───────────────────────────────────────────────────────────
    private fun fetchLocation() {
        lastLocation = CollectorStats.getLastLocation(this)
        updateGpsIndicator()

        // Refresh GPS tiap 10 detik
        binding.root.postDelayed({
            lastLocation = CollectorStats.getLastLocation(this)
            updateGpsIndicator()
        }, 10_000)
    }

    private fun updateGpsIndicator() {
        val loc = lastLocation
        if (loc != null) {
            binding.tvSelectedClass.hint?.let {}
            // Tampilkan status GPS kecil di tvTotalPhotos
            val total = CollectorStats.getTotal(this)
            binding.tvTotalPhotos.text =
                "Total: $total foto  📍 GPS ±${loc.accuracy.toInt()}m"
        } else {
            val total = CollectorStats.getTotal(this)
            binding.tvTotalPhotos.text = "Total: $total foto  📍 GPS mencari..."
        }
    }

    // ── Camera ────────────────────────────────────────────────────────
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal buka kamera: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Class grid ────────────────────────────────────────────────────
    private fun setupClassGrid() {
        val adapter = ClassGridAdapter(RoadClasses.ALL_CLASSES) { roadClass ->
            selectedClass = roadClass
            binding.tvSelectedClass.text = "${roadClass.code} — ${roadClass.nameId}"
            binding.tvSelectedClass.setBackgroundColor(
                android.graphics.Color.parseColor(roadClass.colorHex)
            )
            lastPhotoUri?.let { uri ->
                savePhotoToClass(uri, roadClass)
                lastPhotoUri = null
            }
        }
        binding.rvClasses.layoutManager = GridLayoutManager(this, 2)
        binding.rvClasses.adapter = adapter
    }

    // ── Take photo ────────────────────────────────────────────────────
    private fun takePhoto() {
        val imgCapture = imageCapture ?: return

        // Refresh GPS setiap foto
        lastLocation = CollectorStats.getLastLocation(this)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val tempFile  = File(cacheDir, "temp_$timestamp.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        imgCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lastPhotoUri = Uri.fromFile(tempFile)
                    binding.imgPreview.setImageURI(lastPhotoUri)
                    binding.imgPreview.visibility = android.view.View.VISIBLE

                    val cls = selectedClass
                    if (cls != null) {
                        savePhotoToClass(lastPhotoUri!!, cls)
                        lastPhotoUri = null
                    } else {
                        Toast.makeText(this@CollectorActivity,
                            "Pilih class di bawah untuk menyimpan foto",
                            Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@CollectorActivity,
                        "Gagal foto: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ── Save photo + GPS JSON ─────────────────────────────────────────
    private fun savePhotoToClass(sourceUri: Uri, roadClass: RoadClasses.RoadClass) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
                Locale.getDefault()).format(Date())
            val filename  = "${roadClass.code}_${timestamp}.jpg"

            // Standardize dulu ke temp file → 640×640, koreksi rotasi
            val tempStd = File(cacheDir, "std_$filename")
            val success = ImageStandardizer.standardize(this, sourceUri, tempStd)
            if (!success) {
                Toast.makeText(this, "Gagal standardize foto", Toast.LENGTH_SHORT).show()
                return
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/RoadSenseCollector/" +
                            "${roadClass.code}_${roadClass.nameId}")
            }

            val resolver = contentResolver
            val destUri  = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            destUri?.let { dest ->
                // Copy dari temp standardized file
                resolver.openOutputStream(dest)?.use { output ->
                    tempStd.inputStream().use { input -> input.copyTo(output) }
                }
                tempStd.delete() // hapus temp

                CollectorStats.saveGpsJson(
                    context       = this,
                    classCode     = roadClass.code,
                    photoFilename = filename,
                    location      = lastLocation
                )
                CollectorStats.increment(this, roadClass.code)
                showSuccessAnimation(roadClass)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal simpan: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Import gallery ────────────────────────────────────────────────
    private fun showClassPickerForImport(uris: List<Uri>) {
        val dialog = ClassPickerDialog(this, RoadClasses.ALL_CLASSES) { roadClass ->
            var saved = 0
            uris.forEach { uri ->
                savePhotoToClass(uri, roadClass)
                saved++
            }
            Toast.makeText(this,
                "✅ $saved foto disimpan ke ${roadClass.code}",
                Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    // ── Success animation ─────────────────────────────────────────────
    private fun showSuccessAnimation(roadClass: RoadClasses.RoadClass) {
        val gpsInfo = lastLocation?.let { "📍 ${String.format("%.5f", it.latitude)}, ${String.format("%.5f", it.longitude)}" } ?: "📍 No GPS"
        binding.tvSaveConfirm.text =
            "✅ ${roadClass.code} — ${roadClass.nameId}\n$gpsInfo"
        binding.tvSaveConfirm.visibility = android.view.View.VISIBLE
        binding.tvSaveConfirm.postDelayed({
            binding.tvSaveConfirm.visibility = android.view.View.GONE
            binding.imgPreview.visibility    = android.view.View.GONE
        }, 2000)

        updateGpsIndicator()
    }
}