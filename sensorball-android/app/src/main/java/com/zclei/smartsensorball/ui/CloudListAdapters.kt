package com.zclei.smartsensorball.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zclei.smartsensorball.cloud.CloudLeaderboardEntry
import com.zclei.smartsensorball.cloud.CloudTrainingHistoryItem

internal class LeaderboardRowAdapter(
    private val cardBuilder: (CloudLeaderboardEntry) -> View,
) : ListAdapter<CloudLeaderboardEntry, LeaderboardRowAdapter.VH>(LeaderboardDiff) {

    class VH(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val frame = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return VH(frame)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.frame.removeAllViews()
        holder.frame.addView(cardBuilder(getItem(position)))
    }

    private object LeaderboardDiff : DiffUtil.ItemCallback<CloudLeaderboardEntry>() {
        override fun areItemsTheSame(old: CloudLeaderboardEntry, new: CloudLeaderboardEntry): Boolean =
            old.userId == new.userId

        override fun areContentsTheSame(old: CloudLeaderboardEntry, new: CloudLeaderboardEntry): Boolean =
            old == new
    }
}

internal class HistoryItemAdapter(
    private val cardBuilder: (CloudTrainingHistoryItem) -> View,
) : ListAdapter<CloudTrainingHistoryItem, HistoryItemAdapter.VH>(HistoryDiff) {

    class VH(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val frame = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return VH(frame)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.frame.removeAllViews()
        holder.frame.addView(cardBuilder(getItem(position)))
    }

    private object HistoryDiff : DiffUtil.ItemCallback<CloudTrainingHistoryItem>() {
        override fun areItemsTheSame(old: CloudTrainingHistoryItem, new: CloudTrainingHistoryItem): Boolean =
            old.sessionId == new.sessionId

        override fun areContentsTheSame(old: CloudTrainingHistoryItem, new: CloudTrainingHistoryItem): Boolean =
            old == new
    }
}

internal class VerticalSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.getChildAdapterPosition(view) > 0) {
            outRect.top = spacing
        }
    }
}
