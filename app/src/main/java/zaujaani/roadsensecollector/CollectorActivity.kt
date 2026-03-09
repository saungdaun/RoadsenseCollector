package zaujaani.roadsensecollector

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) startCamera()
        else Toast.makeText(this, "Izin kamera diperlukan!", Toast.LENGTH_SHORT).show()
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

        // Update total
        binding.tvTotalPhotos.text = "Total: ${CollectorStats.getTotal(this)} foto"

        checkPermissions()
    }

    private fun setupClassGrid() {
        val adapter = ClassGridAdapter(RoadClasses.ALL_CLASSES) { roadClass ->
            selectedClass = roadClass
            binding.tvSelectedClass.text = "${roadClass.code} — ${roadClass.nameId}"
            binding.tvSelectedClass.setBackgroundColor(
                android.graphics.Color.parseColor(roadClass.colorHex)
            )
            // Kalau ada foto pending → langsung simpan
            lastPhotoUri?.let { uri ->
                savePhotoToClass(uri, roadClass)
                lastPhotoUri = null
            }
        }
        binding.rvClasses.layoutManager = GridLayoutManager(this, 2)
        binding.rvClasses.adapter = adapter
    }

    private fun checkPermissions() {
        val permissions = arrayOf(Manifest.permission.CAMERA)
        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) startCamera()
        else permissionLauncher.launch(permissions)
    }

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

    private fun takePhoto() {
        val imgCapture = imageCapture ?: return
        val timestamp  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val tempFile   = File(cacheDir, "temp_$timestamp.jpg")
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

    private fun savePhotoToClass(sourceUri: Uri, roadClass: RoadClasses.RoadClass) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
                Locale.getDefault()).format(Date())
            val filename  = "${roadClass.code}_${timestamp}.jpg"

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
                resolver.openInputStream(sourceUri)?.use { input ->
                    resolver.openOutputStream(dest)?.use { output ->
                        input.copyTo(output)
                    }
                }
                CollectorStats.increment(this, roadClass.code)
                showSuccessAnimation(roadClass)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal simpan: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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

    private fun showSuccessAnimation(roadClass: RoadClasses.RoadClass) {
        binding.tvSaveConfirm.text = "✅ Disimpan ke ${roadClass.code} — ${roadClass.nameId}"
        binding.tvSaveConfirm.visibility = android.view.View.VISIBLE
        binding.tvSaveConfirm.postDelayed({
            binding.tvSaveConfirm.visibility = android.view.View.GONE
            binding.imgPreview.visibility    = android.view.View.GONE
        }, 1500)

        binding.tvTotalPhotos.text = "Total: ${CollectorStats.getTotal(this)} foto"
    }
}