package com.forgepulse.anymovie

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.forgepulse.anymovie.databinding.ItemDashboardShelfMovieBinding
import java.util.Locale

class DashboardShelfAdapter(
    private val onClick: (UserLibraryEntry) -> Unit,
) : ListAdapter<UserLibraryEntry, DashboardShelfAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemDashboardShelfMovieBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemDashboardShelfMovieBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UserLibraryEntry) = with(binding) {
            movieTitle.text = item.title
            movieMeta.text = buildString {
                item.year?.let { append(it) }
                item.tmdbRating?.takeIf { it > 0.0 }?.let {
                    if (isNotEmpty()) append("  •  ")
                    append("★ ")
                    append(String.format(Locale.US, "%.1f", it))
                }
                if (isEmpty()) append(if (item.mediaType == "tv") root.context.getString(R.string.tv_label) else root.context.getString(R.string.movie_label))
            }
            progress.progress = (item.progressFraction * 100).toInt()
            progress.visibility = if (item.progressMs > 0L && !item.watched) View.VISIBLE else View.GONE
            watchedBadge.visibility = if (item.watched) View.VISIBLE else View.GONE
            SimpleImageLoader.load(poster, item.poster)
            root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<UserLibraryEntry>() {
            override fun areItemsTheSame(oldItem: UserLibraryEntry, newItem: UserLibraryEntry) = oldItem.catalogId == newItem.catalogId
            override fun areContentsTheSame(oldItem: UserLibraryEntry, newItem: UserLibraryEntry) = oldItem == newItem
        }
    }
}
