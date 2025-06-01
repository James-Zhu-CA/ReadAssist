package com.readassist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.readassist.R
import com.readassist.database.ChatSessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(
    private val onItemClick: (ChatSessionEntity) -> Unit,
    private val onItemLongClick: (ChatSessionEntity) -> Boolean
) : ListAdapter<ChatSessionEntity, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view, onItemClick, onItemLongClick)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SessionViewHolder(
        itemView: View,
        private val onItemClick: (ChatSessionEntity) -> Unit,
        private val onItemLongClick: (ChatSessionEntity) -> Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val tvBookName: TextView = itemView.findViewById(R.id.tvBookName)
        private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val tvMessageCount: TextView = itemView.findViewById(R.id.tvMessageCount)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        
        fun bind(session: ChatSessionEntity) {
            tvBookName.text = session.bookName.ifEmpty { "未知书籍" }
            tvAppName.text = getAppDisplayName(session.appPackage)
            tvDate.text = dateFormat.format(Date(session.lastMessageTime))
            tvMessageCount.text = "${session.messageCount}条消息"
            
            // 显示归档状态
            if (session.isArchived) {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "已归档"
            } else {
                tvStatus.visibility = View.GONE
            }
            
            // 设置点击事件
            itemView.setOnClickListener { onItemClick(session) }
            itemView.setOnLongClickListener { onItemLongClick(session) }
        }
        
        private fun getAppDisplayName(packageName: String): String {
            return when (packageName) {
                "com.supernote.document" -> "Supernote Document"
                "com.ratta.supernote.launcher" -> "Supernote Launcher"
                "com.adobe.reader" -> "Adobe Reader"
                "com.kingsoft.moffice_eng" -> "WPS Office"
                else -> packageName
            }
        }
    }
    
    class SessionDiffCallback : DiffUtil.ItemCallback<ChatSessionEntity>() {
        override fun areItemsTheSame(oldItem: ChatSessionEntity, newItem: ChatSessionEntity): Boolean {
            return oldItem.sessionId == newItem.sessionId
        }
        
        override fun areContentsTheSame(oldItem: ChatSessionEntity, newItem: ChatSessionEntity): Boolean {
            return oldItem == newItem
        }
    }
} 