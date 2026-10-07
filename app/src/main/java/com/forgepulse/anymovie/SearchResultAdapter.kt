package com.forgepulse.anymovie

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.forgepulse.anymovie.databinding.ItemSearchResultBinding

class SearchResultAdapter(
    private val onPlay: (SearchResult) -> Unit,
    private val onDownload: (SearchResult) -> Unit,
    private val onDetails: (SearchResult) -> Unit,
) : RecyclerView.Adapter<SearchResultAdapter.Holder>() {
    private val items = mutableListOf<SearchResult>()

    fun submit(values: List<SearchResult>) {
        items.clear()
        items.addAll(values.filter { it.playable && (!it.playUrl.isNullOrBlank() || !it.hlsUrl.isNullOrBlank()) })
        notifyDataSetChanged()
    }

    fun count(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SearchResult) = with(binding) {
            resultTitle.text = item.title
            resultProvider.text = item.provider
            resultDescription.text = item.description.ifBlank { item.reason }
            resultAction.setText(R.string.play)
            resultAction.setOnClickListener { onPlay(item) }
            resultDetails.setOnClickListener { onDetails(item) }
            resultMediaType.visibility = if (item.kind == null) View.GONE else View.VISIBLE
            resultMediaType.text = buildList {
                add(
                    when {
                        item.kind == "hls" && item.hlsLive -> root.context.getString(R.string.stream_hls_live)
                        item.kind == "hls" && item.hlsVariantCount > 0 -> root.context.getString(R.string.stream_hls_variants, item.hlsVariantCount)
                        item.kind == "hls" -> root.context.getString(R.string.stream_hls)
                        item.kind == "video" -> root.context.getString(R.string.stream_video)
                        item.kind == "embed" -> root.context.getString(R.string.stream_embed)
                        else -> root.context.getString(R.string.stream_video)
                    },
                )
                if (item.contentType == "full_movie") add(root.context.getString(R.string.full_movie_badge))
                if (item.subtitleLanguages.any { it.equals("ar", ignoreCase = true) }) {
                    add(root.context.getString(R.string.subtitle_badge_ar))
                } else if (item.subtitleLanguages.isNotEmpty()) {
                    add(root.context.getString(R.string.subtitle_badge_generic, item.subtitleLanguages.joinToString(", ").uppercase()))
                }
            }.joinToString(" • ")
            resultDownload.visibility = if (item.downloadable && !item.downloadUrl.isNullOrBlank()) View.VISIBLE else View.GONE
            resultDownload.setOnClickListener { onDownload(item) }
            root.alpha = 0f
            root.translationY = 18f
            root.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }
}
