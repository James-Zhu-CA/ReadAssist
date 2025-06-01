package com.readassist.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.readassist.R
import com.readassist.ReadAssistApplication
import com.readassist.database.ChatSessionEntity
import com.readassist.databinding.ActivityHistoryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var app: ReadAssistApplication
    private lateinit var adapter: SessionAdapter
    private var isShowingArchived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as ReadAssistApplication

        // 设置标题栏
        supportActionBar?.title = "历史记录"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 初始化RecyclerView
        setupRecyclerView()

        // 加载历史会话数据
        loadSessionData()

        // 设置导出按钮
        binding.fabExport.setOnClickListener {
            showExportOptions()
        }

        // 设置过滤按钮
        binding.toggleArchived.setOnClickListener {
            isShowingArchived = !isShowingArchived
            binding.toggleArchived.text = if (isShowingArchived) "显示活跃会话" else "显示归档会话"
            loadSessionData()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.history_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.menu_export_all -> {
                exportAllHistory()
                true
            }
            R.id.menu_search -> {
                // TODO: 实现搜索功能
                Toast.makeText(this, "搜索功能即将推出", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = SessionAdapter(
            onItemClick = { session ->
                // 打开会话详情
                openSessionDetail(session)
            },
            onItemLongClick = { session ->
                // 显示操作菜单
                showSessionOptions(session)
                true
            }
        )
        binding.recyclerViewSessions.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewSessions.adapter = adapter
    }

    private fun loadSessionData() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            
            // 根据过滤状态加载不同的会话数据
            val sessionsFlow = if (isShowingArchived) {
                app.chatRepository.getAllSessions()
            } else {
                app.chatRepository.getActiveSessions()
            }

            sessionsFlow.collectLatest { sessions ->
                adapter.submitList(sessions)
                binding.progressBar.visibility = View.GONE
                binding.emptyView.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openSessionDetail(session: ChatSessionEntity) {
        val intent = android.content.Intent(this, SessionDetailActivity::class.java).apply {
            putExtra(SessionDetailActivity.EXTRA_SESSION_ID, session.sessionId)
            putExtra(SessionDetailActivity.EXTRA_BOOK_NAME, session.bookName)
        }
        startActivity(intent)
    }

    private fun showSessionOptions(session: ChatSessionEntity) {
        val options = arrayOf(
            if (session.isArchived) "取消归档" else "归档",
            "导出会话",
            "删除会话"
        )

        AlertDialog.Builder(this)
            .setTitle("会话操作")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleArchiveStatus(session)
                    1 -> exportSession(session)
                    2 -> confirmDeleteSession(session)
                }
            }
            .show()
    }

    private fun toggleArchiveStatus(session: ChatSessionEntity) {
        lifecycleScope.launch {
            app.chatRepository.archiveSession(session.sessionId, !session.isArchived)
            Toast.makeText(
                this@HistoryActivity,
                if (!session.isArchived) "会话已归档" else "会话已取消归档",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun exportSession(session: ChatSessionEntity) {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                val content = app.chatRepository.exportChatHistory(session.sessionId)
                saveToFile(content, "ReadAssist_${session.bookName}_导出.txt")
                binding.progressBar.visibility = View.GONE
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@HistoryActivity, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportAllHistory() {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                val content = app.chatRepository.exportChatHistory()
                saveToFile(content, "ReadAssist_全部历史_导出.txt")
                binding.progressBar.visibility = View.GONE
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@HistoryActivity, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
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

    private fun confirmDeleteSession(session: ChatSessionEntity) {
        AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("确定要删除此会话吗？这将永久删除所有相关消息，且无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                deleteSession(session)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSession(session: ChatSessionEntity) {
        lifecycleScope.launch {
            app.chatRepository.deleteSession(session.sessionId)
            Toast.makeText(this@HistoryActivity, "会话已删除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExportOptions() {
        val options = arrayOf("导出全部历史", "导出活跃会话", "导出归档会话")

        AlertDialog.Builder(this)
            .setTitle("导出选项")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportAllHistory()
                    1 -> exportFilteredSessions(false)
                    2 -> exportFilteredSessions(true)
                }
            }
            .show()
    }

    private fun exportFilteredSessions(archived: Boolean) {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                
                // 获取会话列表
                val sessionsFlow = if (archived) {
                    app.chatRepository.getAllSessions()
                } else {
                    app.chatRepository.getActiveSessions()
                }
                
                val sessions = sessionsFlow.collectLatest { sessionList ->
                    val filteredSessions = if (archived) {
                        sessionList.filter { it.isArchived }
                    } else {
                        sessionList.filter { !it.isArchived }
                    }
                    
                    if (filteredSessions.isEmpty()) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@HistoryActivity, "没有可导出的会话", Toast.LENGTH_SHORT).show()
                        return@collectLatest
                    }
                    
                    // 构建导出内容
                    var content = "ReadAssist 聊天记录导出\n"
                    content += "导出时间: ${Date()}\n"
                    content += "包含: ${if (archived) "归档会话" else "活跃会话"}\n"
                    content += "=".repeat(50) + "\n\n"
                    
                    for (session in filteredSessions) {
                        // 导出每个会话的内容
                        content += app.chatRepository.exportChatHistory(session.sessionId)
                        content += "\n" + "=".repeat(50) + "\n\n"
                    }
                    
                    saveToFile(
                        content,
                        "ReadAssist_${if (archived) "归档会话" else "活跃会话"}_导出.txt"
                    )
                    binding.progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@HistoryActivity, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
} 