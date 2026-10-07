package com.forgepulse.anymovie

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.forgepulse.anymovie.databinding.ItemLibraryBinding

class LibraryAdapter(
    private val onClick: (LibraryItem) -> Unit,
    private val onDetails: (LibraryItem) -> Unit,
) : RecyclerView.Adapter<LibraryAdapter.Holder>() {
    private val items = mutableListOf<LibraryItem>()

    fun submit(values: List<LibraryItem>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    fun add(item: LibraryItem) {
        items.removeAll { it.uri == item.uri }
        items.add(0, item)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemLibraryBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemLibraryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LibraryItem) = with(binding) {
            libraryTitle.text = item.title
            librarySource.text = buildString {
                append(item.source)
                append(" • ")
                append(when (item.kind) {
                    "hls" -> root.context.getString(R.string.stream_hls)
                    "embed" -> root.context.getString(R.string.stream_embed)
                    else -> root.context.getString(R.string.stream_video)
                })
            }
            root.setOnClickListener { onClick(item) }
            libraryDetails.setOnClickListener { onDetails(item) }
        }
    }
}
