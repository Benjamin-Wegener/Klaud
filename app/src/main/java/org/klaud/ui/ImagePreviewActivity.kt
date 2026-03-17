package org.klaud.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.github.chrisbanes.photoview.PhotoView
import org.klaud.FileRepository
import org.klaud.databinding.ActivityImagePreviewBinding
import java.io.File

class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImagePreviewBinding
    private lateinit var images: List<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val relativePath = intent.getStringExtra("relativePath") ?: return finish()
        val currentFile = FileRepository.getFileByRelativePath(relativePath)
        val parentDir = currentFile.parentFile ?: FileRepository.getSyncRoot()

        images = parentDir.listFiles()?.filter { 
            FileRepository.getMimeType(it).startsWith("image/") 
        }?.sortedBy { it.name } ?: emptyList()

        val initialPosition = images.indexOfFirst { it.absolutePath == currentFile.absolutePath }.coerceAtLeast(0)

        binding.viewPager.adapter = ImagePagerAdapter(images)
        binding.viewPager.setCurrentItem(initialPosition, false)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                supportActionBar?.title = images[position].name
            }
        })
    }

    inner class ImagePagerAdapter(private val imageFiles: List<File>) : RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {
        inner class ViewHolder(val photoView: PhotoView) : RecyclerView.ViewHolder(photoView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val photoView = PhotoView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return ViewHolder(photoView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.photoView.load(imageFiles[position])
        }

        override fun getItemCount(): Int = imageFiles.size
    }
}
