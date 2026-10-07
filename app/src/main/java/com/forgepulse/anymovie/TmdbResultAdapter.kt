package com.forgepulse.anymovie

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.forgepulse.anymovie.databinding.ItemTmdbResultBinding
import java.util.Locale

class TmdbResultAdapter(
    private val isAlreadySaved: (Int) -> Boolean,
    private val onAdd: (TmdbSearchResult) -> Unit,
) : ListAdapter<TmdbSearchResult, TmdbResultAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemTmdbResultBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemTmdbResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TmdbSearchResult) = with(binding) {
            title.text = item.title
            meta.text = buildString {
                append(if (item.mediaType == "tv") root.context.getString(R.string.tv_label) else root.context.getString(R.string.movie_label))
                item.year?.let { append("  •  $it") }
                item.rating?.takeIf { it > 0.0 }?.let { append("  •  ★ ${String.format(Locale.US, "%.1f", it)}") }
            }
            description.text = item.overview ?: root.context.getString(R.string.tmdb_no_overview)
            SimpleImageLoader.load(poster, item.poster)
            val saved = isAlreadySaved(item.tmdbId)
            addButton.isEnabled = !saved
            addButton.text = root.context.getString(if (saved) R.string.in_library else R.string.add_to_library)
            addButton.setOnClickListener { if (!saved) onAdd(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TmdbSearchResult>() {
            override fun areItemsTheSame(oldItem: TmdbSearchResult, newItem: TmdbSearchResult) = oldItem.tmdbId == newItem.tmdbId && oldItem.mediaType == newItem.mediaType
            override fun areContentsTheSame(oldItem: TmdbSearchResult, newItem: TmdbSearchResult) = oldItem == newItem
        }
    }
}
