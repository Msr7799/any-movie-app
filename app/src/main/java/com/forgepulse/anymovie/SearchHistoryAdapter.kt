package com.forgepulse.anymovie

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.forgepulse.anymovie.databinding.ItemSearchHistoryBinding
import java.text.DateFormat
import java.util.Date

class SearchHistoryAdapter(
    private val onClick: (SavedSearch) -> Unit,
) : RecyclerView.Adapter<SearchHistoryAdapter.Holder>() {
    private val items = mutableListOf<SavedSearch>()

    fun submit(values: List<SavedSearch>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemSearchHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemSearchHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SavedSearch) = with(binding) {
            historyQuery.text = item.draft.query
            historyMeta.text = root.context.getString(
                R.string.search_history_meta,
                item.response.results.size,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.savedAt)),
            )
            root.setOnClickListener { onClick(item) }
        }
    }
}
