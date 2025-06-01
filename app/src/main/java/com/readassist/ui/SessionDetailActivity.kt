package com.readassist.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.readassist.R
import com.readassist.ReadAssistApplication
import com.readassist.database.ChatEntity
import com.readassist.databinding.ActivitySessionDetailBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_BOOK_NAME = "extra_book_name"
    }

    private lateinit var binding: ActivitySessionDetailBinding
    private lateinit var app: ReadAssistApplication
    private lateinit var sessionId: String
    private lateinit var bookName: String
    private lateinit var adapter: ChatMessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as ReadAssistApplication

        // 获取会话ID
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        bookName = intent.getStringExtra(EXTRA_BOOK_NAME) ?: "未知书籍"

        if (sessionId.isEmpty()) {
            Toast.makeText(this, "无效的会话ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 设置标题栏
        supportActionBar?.title = bookName
        supportActionBar?.subtitle = "会话详情"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 初始化RecyclerView
        setupRecyclerView()

        // 加载会话消息
        loadSessionMessages()

        // 设置导出按钮
        binding.fabExport.setOnClickListener {
            exportSession()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.session_detail_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.menu_export -> {
                exportSession()
                true
            }
            R.id.menu_delete -> {
                confirmDeleteSession()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = ChatMessageAdapter(
            onBookmarkToggle = { message, isBookmarked ->
                toggleMessageBookmark(message.id, isBookmarked)
            }
        )
        binding.recyclerViewMessages.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewMessages.adapter = adapter
    }

    private fun loadSessionMessages() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            
            app.chatRepository.getChatMessages(sessionId).collectLatest { messages ->
                adapter.submitList(messages)
                binding.progressBar.visibility = View.GONE
                
                // 如果消息列表为空，显示空视图
                binding.emptyView.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                
                // 滚动到底部
                if (messages.isNotEmpty()) {
                    binding.recyclerViewMessages.post {
                        binding.recyclerViewMessages.scrollToPosition(messages.size - 1)
                    }
                }
            }
        }
    }

    private fun toggleMessageBookmark(messageId: Long, isBookmarked: Boolean) {
        lifecycleScope.launch {
            app.chatRepository.toggleBookmark(messageId, isBookmarked)
            Toast.makeText(
                this@SessionDetailActivity,
                if (isBookmarked) "已收藏消息" else "已取消收藏",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun exportSession() {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                val content = app.chatRepository.exportChatHistory(sessionId)
                saveToFile(content, "ReadAssist_${bookName}_导出.txt")
                binding.progressBar.visibility = View.GONE
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@SessionDetailActivity, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToFile(content: String, suggestedName: String) {
        try {
            // 生成带时间戳的文件名
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = suggestedName.replace(".txt", "_$timestamp.txt")
            
            // 创建导出目录
            val exportDir = File(getExternalFilesDir(null), "exports")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            
            // 创建文件并写入内容
            val file = File(exportDir, fileName)
            file.writeText(content)
            
            // 显示成功消息
            Toast.makeText(
                this,
                "导出成功: ${file.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
            
            // 可选：触发系统媒体扫描
            val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = android.net.Uri.fromFile(file)
            sendBroadcast(mediaScanIntent)
            
        } catch (e: Exception) {
            Toast.makeText(this, "保存文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSession() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("确定要删除此会话吗？这将永久删除所有相关消息，且无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                deleteSession()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSession() {
        lifecycleScope.launch {
            app.chatRepository.deleteSession(sessionId)
            Toast.makeText(this@SessionDetailActivity, "会话已删除", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
} 