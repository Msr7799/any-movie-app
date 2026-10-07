package com.forgepulse.anymovie

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.forgepulse.anymovie.databinding.ItemDashboardMovieBinding

class DashboardMovieAdapter(
    private val onClick: (UserLibraryEntry) -> Unit,
) : RecyclerView.Adapter<DashboardMovieAdapter.Holder>() {
    private var items: List<UserLibraryEntry> = emptyList()

    fun submit(values: List<UserLibraryEntry>) {
        items = values
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemDashboardMovieBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemDashboardMovieBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UserLibraryEntry) = with(binding) {
            title.text = item.title
            subtitle.text = buildString {
                append(if (item.mediaType == "tv") root.context.getString(R.string.tv_label) else root.context.getString(R.string.movie_label))
                if (item.rating > 0) append("  •  ★ ${item.rating}/10")
                if (item.watched) append("  •  ${root.context.getString(R.string.watched_label)}")
            }
            progress.progress = (item.progressFraction * 100).toInt()
            progress.visibility = if (item.progressMs > 0 && !item.watched) android.view.View.VISIBLE else android.view.View.GONE
            watchlist.text = if (item.watchlist) "♡" else "＋"
            SimpleImageLoader.load(poster, item.poster)
            root.setOnClickListener { onClick(item) }
        }
    }
}
