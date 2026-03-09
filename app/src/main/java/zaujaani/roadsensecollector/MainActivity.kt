package zaujaani.roadsensecollector

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import zaujaani.roadsensecollector.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCamera.setOnClickListener {
            startActivity(Intent(this, CollectorActivity::class.java))
        }
        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryBrowserActivity::class.java))
        }
        binding.btnVideoExtractor.setOnClickListener {
            startActivity(Intent(this, VideoExtractorActivity::class.java))
        }
        binding.btnLibrary.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        binding.btnExport.setOnClickListener {
            startActivity(Intent(this, ExportActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val total = CollectorStats.getTotal(this)
        binding.tvTotalPhotos.text = "Total: $total foto"
    }
}