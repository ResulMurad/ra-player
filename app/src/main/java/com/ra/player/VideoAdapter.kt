package com.ra.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ra.player.databinding.ItemVideoBinding

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VH>(Diff) {

    class VH(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)

    object Diff : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(old: VideoItem, new: VideoItem) = old.uri == new.uri
        override fun areContentsTheSame(old: VideoItem, new: VideoItem) = old == new
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = getItem(position)
        val b = holder.b
        val ctx = b.root.context

        b.title.text = v.title
        b.meta.text = listOfNotNull(
            Fmt.size(v.sizeBytes).ifBlank { null },
            if (v.width > 0 && v.height > 0) "${v.width}×${v.height}" else null,
            v.folder.ifBlank { null }
        ).joinToString("  ·  ")
        b.duration.text = Fmt.time(v.durationMs)

        val saved = Resume.get(ctx, v.uri)
        if (saved > 0 && v.durationMs > 0) {
            b.progress.visibility = View.VISIBLE
            b.progress.progress = ((saved * 1000) / v.durationMs).toInt()
        } else {
            b.progress.visibility = View.GONE
        }

        Glide.with(b.thumb).load(v.uri).centerCrop().into(b.thumb)
        b.root.setOnClickListener { onClick(v) }
    }
}
