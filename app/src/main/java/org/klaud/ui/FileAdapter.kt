package org.klaud.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.klaud.FileRepository
import org.klaud.R
import org.klaud.StorageHelper
import java.text.SimpleDateFormat
import java.util.*

class FileAdapter(
    private val onFileClick: (FileRepository.SyncFile) -> Unit,
    private val onFileLongClick: (FileRepository.SyncFile, View) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    private var items = listOf<FileRepository.SyncFile>()

    fun updateItems(newItems: List<FileRepository.SyncFile>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isDirectory) VIEW_TYPE_FOLDER else VIEW_TYPE_FILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.fileIcon)
        private val name: TextView = itemView.findViewById(R.id.fileName)
        private val subtitle: TextView = itemView.findViewById(R.id.fileSubtitle)
        private val menuBtn: ImageButton = itemView.findViewById(R.id.fileMenuBtn)

        fun bind(item: FileRepository.SyncFile) {
            val iconRes = when {
                item.isDirectory -> R.drawable.ic_folder
                item.file.extension.lowercase() in listOf("jpg","jpeg","png","webp","gif") ->
                    R.drawable.ic_image
                item.file.extension.lowercase() in listOf("mp4","mkv","avi","mov") ->
                    R.drawable.ic_video
                item.file.extension.lowercase() in listOf("pdf") ->
                    R.drawable.ic_pdf
                item.file.extension.lowercase() in listOf("apk") ->
                    R.drawable.ic_apk
                else -> R.drawable.ic_file
            }
            icon.setImageResource(iconRes)
            val tintColor = if (item.isDirectory)
                ContextCompat.getColor(itemView.context, R.color.klaud_icon_folder)
            else
                ContextCompat.getColor(itemView.context, R.color.klaud_icon_file)
            icon.setColorFilter(tintColor)

            name.text = item.file.name

            subtitle.text = if (item.isDirectory) {
                val count = item.file.listFiles()?.size ?: 0
                "$count items"
            } else {
                val sizeStr = StorageHelper.formatBytes(item.file.length())
                val date = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                    .format(Date(item.file.lastModified()))
                "$sizeStr · $date"
            }

            menuBtn.setOnClickListener { view ->
                onFileLongClick(item, view)
            }

            itemView.setOnClickListener { onFileClick(item) }
        }
    }

    companion object {
        const val VIEW_TYPE_FOLDER = 1
        const val VIEW_TYPE_FILE = 2
    }
}
