package zaujaani.roadsensecollector

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
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
    private var intervalSeconds         = 3
    private var extractJob              : Job? = null
    private var wakeLock                : PowerManager.WakeLock? = null

    // ── YouTube download ──────────────────────────────────────────────
    private var activeDownloadId        : Long = -1L
    private var downloadReceiver        : BroadcastReceiver? = null

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

    // Crop launcher — menerima hasil dari CropActivity
    private var pendingCropPosition = -1
    private var onCropDone: ((String) -> Unit)? = null

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val cropPath = result.data?.getStringExtra(CropActivity.EXTRA_CROP_PATH)
            if (cropPath != null) {
                onCropDone?.invoke(cropPath)
            }
        }
        onCropDone = null
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
        unregisterDownloadReceiver()
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

        // ── YouTube button ────────────────────────────────────────────
        binding.btnYoutube.setOnClickListener        { showYoutubeDialog() }

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

    // ── Pick video dari storage ───────────────────────────────────────
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

    // ── Browser Video Dialog (YouTube / Facebook / URL apapun) ──────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun showYoutubeDialog() {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .create()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0D0D.toInt())
        }

        // ── Row 1: Close + Title ──────────────────────────────────────
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(8, 6, 8, 6)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val btnClose = Button(this).apply {
            setText(R.string.btn_close_x)
            setBackgroundColor(0xFF3A3A3A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(88, 72)
        }

        val tvBrowserTitle = TextView(this).apply {
            text = "🌐 Browser Video"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Tombol shortcut: YouTube | Facebook | Custom
        val btnShortcutYt = Button(this).apply {
            text = "YT"
            setBackgroundColor(0xFFCC0000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(72, 72).also { it.marginEnd = 4 }
        }
        val btnShortcutFb = Button(this).apply {
            text = "FB"
            setBackgroundColor(0xFF1877F2.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(72, 72)
        }

        titleBar.addView(btnClose)
        titleBar.addView(tvBrowserTitle)
        titleBar.addView(btnShortcutYt)
        titleBar.addView(btnShortcutFb)

        // ── Row 2: Nav buttons + Address bar + Go ─────────────────────
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF222222.toInt())
            setPadding(6, 4, 6, 4)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val btnNavBack = Button(this).apply {
            text = "◀"
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(72, 80)
        }
        val btnNavForward = Button(this).apply {
            text = "▶"
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(72, 80).also { it.marginStart = 4 }
        }
        val btnNavRefresh = Button(this).apply {
            text = "↺"
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(72, 80).also { it.marginStart = 4 }
        }

        val etUrl = EditText(this).apply {
            hint = "URL atau kata kunci pencarian…"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            setBackgroundColor(0xFF2E2E2E.toInt())
            setPadding(14, 0, 14, 0)
            layoutParams = LinearLayout.LayoutParams(
                0, 80, 1f
            ).also { it.marginStart = 6; it.marginEnd = 6 }
            maxLines = 1
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or
                    android.text.InputType.TYPE_CLASS_TEXT
            textSize = 12f
        }

        val btnGo = Button(this).apply {
            setText(R.string.btn_go)
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(100, 80)
        }

        navBar.addView(btnNavBack)
        navBar.addView(btnNavForward)
        navBar.addView(btnNavRefresh)
        navBar.addView(etUrl)
        navBar.addView(btnGo)

        // ── Progress bar ──────────────────────────────────────────────
        val webProgress = ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 6
            )
            visibility = View.GONE
        }

        // ── Info bar ──────────────────────────────────────────────────
        // Penjelasan jujur: YouTube tidak bisa didownload via DownloadManager karena enkripsi.
        // Yang bisa: video langsung (.mp4/.webm) dari situs lain, atau screen record manual.
        val tvInfo = TextView(this).apply {
            text = "ℹ️ YouTube & Facebook tidak bisa didownload otomatis (terenkripsi). " +
                    "Tombol Download aktif hanya untuk video langsung (.mp4/.webm). " +
                    "Alternatif: tonton di sini → screen record HP."
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 10f
            setPadding(12, 6, 12, 6)
            setBackgroundColor(0xFF1A1F1A.toInt())
        }

        // ── WebView ───────────────────────────────────────────────────
        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = getString(R.string.webview_user_agent)
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        // ── Download button (bawah, hanya aktif kalau URL direct video) ─
        val btnDownload = Button(this).apply {
            setText(R.string.btn_download_extract)
            setBackgroundColor(0xFF1B5E20.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isEnabled = false
            alpha = 0.4f
        }

        var currentWebUrl = ""

        fun isDirectVideoUrl(url: String?): Boolean {
            if (url.isNullOrEmpty()) return false
            return url.contains(".mp4", ignoreCase = true) ||
                    url.contains(".mkv", ignoreCase = true) ||
                    url.contains(".webm", ignoreCase = true) ||
                    url.contains(".3gp", ignoreCase = true) ||
                    (url.contains("video", ignoreCase = true) &&
                            !url.contains("youtube.com") &&
                            !url.contains("youtu.be") &&
                            !url.contains("facebook.com") &&
                            !url.contains("fb.watch"))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url.startsWith("intent://")) return true
                currentWebUrl = url
                etUrl.setText(url)
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                currentWebUrl = url ?: ""
                if (!url.isNullOrEmpty()) etUrl.setText(url)
                val isDirect = isDirectVideoUrl(url)
                btnDownload.isEnabled = isDirect
                btnDownload.alpha     = if (isDirect) 1f else 0.4f
                btnNavBack.alpha    = if (view?.canGoBack() == true) 1f else 0.35f
                btnNavForward.alpha = if (view?.canGoForward() == true) 1f else 0.35f
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                webProgress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                webProgress.progress   = newProgress
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
            if (mimetype?.startsWith("video") == true ||
                filename.endsWith(".mp4") || filename.endsWith(".webm") ||
                filename.endsWith(".mkv") || filename.endsWith(".3gp")) {
                webView.destroy()
                dialog.dismiss()
                startDownloadAndExtract(url, filename)
            } else {
                Toast.makeText(this,
                    getString(R.string.download_not_video, mimetype), Toast.LENGTH_SHORT).show()
            }
        }

        // Default: search YouTube mobile
        webView.loadUrl("https://m.youtube.com/results?search_query=road+damage+pothole+rutting")
        etUrl.setText("https://m.youtube.com/results?search_query=road+damage+pothole+rutting")

        // ── Actions ───────────────────────────────────────────────────
        fun navigateTo(url: String) {
            val target = if (url.startsWith("http")) url
            else "https://www.google.com/search?q=${Uri.encode(url)}"
            webView.loadUrl(target)
            etUrl.setText(target)
        }

        btnGo.setOnClickListener {
            val input = etUrl.text.toString().trim()
            if (input.isNotBlank()) navigateTo(input)
        }

        etUrl.setOnEditorActionListener { _, _, _ ->
            val input = etUrl.text.toString().trim()
            if (input.isNotBlank()) navigateTo(input)
            true
        }

        btnNavBack.setOnClickListener    { if (webView.canGoBack())    webView.goBack() }
        btnNavForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        btnNavRefresh.setOnClickListener { webView.reload() }

        btnShortcutYt.setOnClickListener {
            navigateTo("https://m.youtube.com/results?search_query=road+damage+pothole")
        }
        btnShortcutFb.setOnClickListener {
            navigateTo("https://m.facebook.com")
        }

        btnClose.setOnClickListener {
            webView.destroy()
            dialog.dismiss()
        }

        btnDownload.setOnClickListener {
            val url = currentWebUrl.ifEmpty { etUrl.text.toString().trim() }
            if (url.isBlank()) {
                Toast.makeText(this, getString(R.string.download_no_url), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val filename = "video_${System.currentTimeMillis()}.mp4"
            webView.destroy()
            dialog.dismiss()
            startDownloadAndExtract(url, filename)
        }

        root.addView(titleBar)
        root.addView(navBar)
        root.addView(webProgress)
        root.addView(tvInfo)
        root.addView(webView)
        root.addView(btnDownload)

        dialog.setView(root)
        dialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        dialog.show()
    }

    // ── Download via DownloadManager ──────────────────────────────────
    private fun startDownloadAndExtract(url: String, filename: String) {
        Toast.makeText(this,
            getString(R.string.download_started, filename), Toast.LENGTH_LONG).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(url, emptyMap())
                val duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                retriever.release()

                if (duration > 0) {
                    withContext(Dispatchers.Main) {
                        val uri = Uri.parse(url)
                        videoUri = uri
                        loadVideoInfo(uri)
                        binding.btnExtract.isEnabled = true
                        Toast.makeText(
                            this@VideoExtractorActivity,
                            getString(R.string.stream_ready),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
            } catch (_: Exception) {
                // Stream langsung gagal → fallback ke DownloadManager
            }

            withContext(Dispatchers.Main) {
                try {
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val req = DownloadManager.Request(Uri.parse(url)).apply {
                        setTitle(getString(R.string.download_title, filename))
                        setDescription(getString(R.string.download_description))
                        setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, "RoadSense/$filename"
                        )
                        addRequestHeader("User-Agent", getString(R.string.webview_user_agent))
                    }
                    activeDownloadId = dm.enqueue(req)
                    registerDownloadReceiver()
                    Toast.makeText(
                        this@VideoExtractorActivity,
                        getString(R.string.download_queued),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@VideoExtractorActivity,
                        getString(R.string.download_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ── DownloadManager receiver ──────────────────────────────────────
    private fun registerDownloadReceiver() {
        if (downloadReceiver != null) return
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != activeDownloadId) return

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = dm.query(query)

                if (cursor.moveToFirst()) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusCol)

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val localUri = cursor.getString(uriCol)
                        cursor.close()

                        val fileUri = Uri.parse(localUri)
                        videoUri = fileUri
                        loadVideoInfo(fileUri)
                        binding.btnExtract.isEnabled = true
                        Toast.makeText(
                            this@VideoExtractorActivity,
                            getString(R.string.download_complete),
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        cursor.close()
                        Toast.makeText(
                            this@VideoExtractorActivity,
                            getString(R.string.download_failed_retry),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    cursor.close()
                }
                unregisterDownloadReceiver()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun unregisterDownloadReceiver() {
        downloadReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            downloadReceiver = null
        }
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

        acquireWakeLock()

        extractJob = lifecycleScope.launch {
            val extracted = withContext(Dispatchers.IO) { extractFrames(uri) }

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
            val uriStr = uri.toString()
            if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                retriever.setDataSource(uriStr, emptyMap())
            } else {
                retriever.setDataSource(this, uri)
            }

            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: return result
            val intervalMs = intervalSeconds * 1000L
            var currentMs  = 0L
            var frameIndex = 0

            while (currentMs <= durationMs) {
                if (!extractJob!!.isActive) break

                val bitmap = retriever.getFrameAtTime(
                    currentMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

                if (bitmap != null) {
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
                val msg = if (frames[position].selected)
                    getString(R.string.frame_marked_hint)
                else
                    getString(R.string.frame_unmarked)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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

        val view = LayoutInflater.from(this)
            .inflate(R.layout.dialog_frame_preview, null)
        dialog.setContentView(view)

        val win = dialog.window
        win?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

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

        // Tombol Crop — ditambahkan secara programmatic di bottom bar
        val bottomBar = view.findViewById<LinearLayout>(R.id.bottomBar)
        val btnCrop = Button(this).apply {
            setText(R.string.btn_crop)
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(0xFF6A1B9A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f
            ).also { it.marginStart = 6 }
        }
        bottomBar?.addView(btnCrop, 0)

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

        fun renderFrame(pos: Int) {
            val frame = frames[pos]

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
            (btnToggleSelect as? Button)?.backgroundTintList =
                ColorStateList.valueOf(
                    ContextCompat.getColor(this,
                        if (isSelected) R.color.select_active else R.color.select_inactive)
                )
        }

        dialog.setOnDismissListener {
            currentBitmap?.recycle()
            currentBitmap = null
        }

        renderFrame(currentPos)

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
            showAssignForFrame(currentPos) { cls ->
                adapter.notifyItemChanged(currentPos)
                updateFrameCount()
                Toast.makeText(
                    this,
                    getString(R.string.assign_direct_success, cls.code, cls.nameId),
                    Toast.LENGTH_SHORT
                ).show()
                if (currentPos < frames.size - 1) {
                    currentPos++
                    renderFrame(currentPos)
                }
            }
        }
        btnCrop.setOnClickListener {
            val frame = frames[currentPos]
            onCropDone = { cropPath ->
                val classList = RoadClasses.getActiveClasses(this)
                val names = classList.map {
                    getString(R.string.class_name_format, it.code, it.nameId)
                }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.assign_crop_title))
                    .setItems(names) { _, which ->
                        val cls = classList[which]
                        lifecycleScope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                val bmp = BitmapFactory.decodeFile(cropPath)
                                if (bmp != null) {
                                    val result = saveFrameToClass(bmp, cls)
                                    bmp.recycle()
                                    try { File(cropPath).delete() } catch (_: Exception) {}
                                    result
                                } else false
                            }
                            if (saved) {
                                CollectorStats.recalculateFromDisk(this@VideoExtractorActivity)
                                Toast.makeText(
                                    this@VideoExtractorActivity,
                                    getString(R.string.assign_crop_success, cls.code, cls.nameId),
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (currentPos < frames.size - 1) {
                                    currentPos++
                                    renderFrame(currentPos)
                                }
                            } else {
                                Toast.makeText(this@VideoExtractorActivity,
                                    getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
            cropLauncher.launch(CropActivity.intent(this, frame.thumbPath))
        }

        btnClose.setOnClickListener { dialog.dismiss() }
    }

    // ── Assign ke class ───────────────────────────────────────────────
    private fun showAssignForFrame(position: Int, onDone: (RoadClasses.RoadClass) -> Unit) {
        val classList = RoadClasses.getActiveClasses(this)
        val names = classList.map {
            getString(R.string.class_name_format, it.code, it.nameId)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.assign_frame_title))
            .setItems(names) { _, which ->
                val cls = classList[which]
                lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        val bmp = frames[position].loadBitmap()
                        if (bmp != null) {
                            val result = saveFrameToClass(bmp, cls)
                            bmp.recycle()
                            result
                        } else false
                    }
                    if (saved) {
                        CollectorStats.recalculateFromDisk(this@VideoExtractorActivity)
                        onDone(cls)
                    } else {
                        Toast.makeText(this@VideoExtractorActivity,
                            getString(R.string.frame_save_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showAssignDialog() {
        val selected = frames.filter { it.selected }
        if (selected.isEmpty()) return

        val classList = RoadClasses.getActiveClasses(this)
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
        var thumbBitmap    : Bitmap?   = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_frame, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val frame      = frames[position]
        val isSelected = frame.selected

        // Fix: gunakan Glide supaya decode file tidak blocking main thread (cegah ANR)
        holder.thumbBitmap?.recycle()
        holder.thumbBitmap = null
        com.bumptech.glide.Glide.with(holder.itemView.context)
            .load(java.io.File(frame.thumbPath))
            .centerCrop()
            .into(holder.img)

        val seconds = frame.timeMs / 1000
        holder.tvTimestamp.text = holder.itemView.context.getString(
            R.string.timestamp_format, seconds / 60, seconds % 60)

        holder.tvBlur.visibility          = if (frame.isBlurry) View.VISIBLE else View.GONE
        holder.overlaySelected.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.tvCheck.visibility         = if (isSelected) View.VISIBLE else View.GONE
        holder.tvTapHint.text             = if (isSelected) "" else
            holder.itemView.context.getString(R.string.frame_tap_hint)
        holder.tvTapHint.visibility       = if (isSelected) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener { onPreview(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongPress(holder.bindingAdapterPosition)
            true
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        // Fix: Glide yang manage lifecycle bitmap — cukup clear request-nya
        com.bumptech.glide.Glide.with(holder.itemView.context).clear(holder.img)
        holder.thumbBitmap?.recycle()
        holder.thumbBitmap = null
    }

    override fun getItemCount() = frames.size
}