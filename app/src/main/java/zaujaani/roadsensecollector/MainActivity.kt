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

        // Update total photos di home screen
        val total = CollectorStats.getTotal(this)
        binding.tvTotalPhotos.text = "Total: $total foto"

        binding.btnCamera.setOnClickListener {
            startActivity(Intent(this, CollectorActivity::class.java))
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
        // Refresh total setiap kali balik ke home
        val total = CollectorStats.getTotal(this)
        binding.tvTotalPhotos.text = "Total: $total foto"
    }
}