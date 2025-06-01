package com.readassist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.readassist.R
import com.readassist.database.ChatEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMessageAdapter(
    private val onBookmarkToggle: (ChatEntity, Boolean) -> Unit
) : ListAdapter<ChatEntity, ChatMessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_AI = 2
    }

    override fun getItemViewType(position: Int): Int {
        // 在这里我们将一个数据库消息分割为用户消息和AI回复两个视图项
        // 我们将连续相邻的项目分别展示为用户消息和AI回复
        return if (position % 2 == 0) VIEW_TYPE_USER else VIEW_TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutRes = when (viewType) {
            VIEW_TYPE_USER -> R.layout.item_message_user
            VIEW_TYPE_AI -> R.layout.item_message_ai
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
        
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false)
        
        return MessageViewHolder(view, onBookmarkToggle)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val messagePosition = position / 2 // 因为每个数据库条目分为两个视图项
        if (messagePosition >= itemCount) {
            return // 防止数组越界
        }
        val message = getItem(messagePosition) // 获取对应的消息
        val isUserMessage = position % 2 == 0
        
        holder.bind(message, isUserMessage)
    }

    override fun getItemCount(): Int {
        // 每个数据库条目分为用户消息和AI回复两个视图项
        return super.getItemCount() * 2
    }

    class MessageViewHolder(
        itemView: View,
        private val onBookmarkToggle: (ChatEntity, Boolean) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTimestamp: TextView? = itemView.findViewById(R.id.tvTimestamp)
        private val btnBookmark: ImageButton? = itemView.findViewById(R.id.btnBookmark)
        
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        fun bind(chatEntity: ChatEntity, isUserMessage: Boolean) {
            // 设置消息内容
            tvMessage.text = if (isUserMessage) chatEntity.userMessage else chatEntity.aiResponse
            
            // 设置时间戳 (仅在AI回复中显示)
            tvTimestamp?.text = dateFormat.format(Date(chatEntity.timestamp))
            
            // 设置收藏按钮 (仅在AI回复中显示)
            btnBookmark?.apply {
                visibility = if (isUserMessage) View.GONE else View.VISIBLE
                setImageResource(
                    if (chatEntity.isBookmarked) R.drawable.ic_bookmark_filled
                    else R.drawable.ic_bookmark_outline
                )
                
                setOnClickListener {
                    val newBookmarkState = !chatEntity.isBookmarked
                    onBookmarkToggle(chatEntity, newBookmarkState)
                }
            }
        }
    }
    
    class MessageDiffCallback : DiffUtil.ItemCallback<ChatEntity>() {
        override fun areItemsTheSame(oldItem: ChatEntity, newItem: ChatEntity): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: ChatEntity, newItem: ChatEntity): Boolean {
            return oldItem == newItem
        }
    }
} 