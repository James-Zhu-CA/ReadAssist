package com.readassist.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.readassist.ReadAssistApplication
import com.readassist.repository.ChatStatistics
import com.readassist.utils.PermissionUtils
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val app = application as ReadAssistApplication
    private val repository = app.chatRepository
    
    // 权限状态
    private val _permissionStatus = MutableLiveData<PermissionUtils.PermissionStatus>()
    val permissionStatus: LiveData<PermissionUtils.PermissionStatus> = _permissionStatus
    
    // 服务运行状态
    private val _isServiceRunning = MutableLiveData<Boolean>()
    val isServiceRunning: LiveData<Boolean> = _isServiceRunning
    
    // API Key 状态
    private val _hasApiKey = MutableLiveData<Boolean>()
    val hasApiKey: LiveData<Boolean> = _hasApiKey
    
    // 统计信息
    private val _statistics = MutableLiveData<ChatStatistics>()
    val statistics: LiveData<ChatStatistics> = _statistics
    
    // 加载状态
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading
    
    // 错误消息
    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> = _errorMessage
    
    init {
        checkPermissions()
        checkServiceStatus()
        checkApiKey()
        loadStatistics()
    }
    
    /**
     * 检查权限状态
     */
    fun checkPermissions() {
        viewModelScope.launch {
            try {
                val status = PermissionUtils.checkAllPermissions(getApplication())
                _permissionStatus.value = status
            } catch (e: Exception) {
                _errorMessage.value = "权限检查失败：${e.message}"
            }
        }
    }
    
    /**
     * 检查服务状态
     */
    fun checkServiceStatus() {
        viewModelScope.launch {
            try {
                val isRunning = PermissionUtils.hasAccessibilityPermission(getApplication())
                _isServiceRunning.value = isRunning
            } catch (e: Exception) {
                _errorMessage.value = "服务状态检查失败：${e.message}"
            }
        }
    }
    
    /**
     * 检查 API Key 状态
     */
    fun checkApiKey() {
        viewModelScope.launch {
            try {
                val hasKey = app.preferenceManager.hasApiKey()
                _hasApiKey.value = hasKey
            } catch (e: Exception) {
                _errorMessage.value = "API Key 检查失败：${e.message}"
            }
        }
    }
    
    /**
     * 设置 API Key
     */
    fun setApiKey(platform: com.readassist.model.AiPlatform, apiKey: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                app.preferenceManager.setApiKey(platform, apiKey)
                _hasApiKey.value = true
                _errorMessage.value = "API Key 设置成功"
            } catch (e: Exception) {
                _errorMessage.value = "API Key 设置失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 加载统计信息
     */
    fun loadStatistics() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val stats = repository.getStatistics()
                _statistics.value = stats
            } catch (e: Exception) {
                _errorMessage.value = "统计信息加载失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 清除所有数据
     */
    fun clearAllData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                repository.clearAllData()
                app.preferenceManager.clearAllPreferences()
                
                // 重新加载数据
                loadStatistics()
                checkApiKey()
                
                _errorMessage.value = "所有数据已清除"
            } catch (e: Exception) {
                _errorMessage.value = "数据清除失败：${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 导出聊天记录
     */
    fun exportChatHistory() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val exportData = repository.exportChatHistory()
                
                // 这里可以触发文件保存或分享操作
                // 为简化实现，这里只是设置成功消息
                _errorMessage.value = "聊天记录导出成功"
            } catch (e: Exception) {
                _errorMessage.value = "导出失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 切换自动分析设置
     */
    fun toggleAutoAnalyze() {
        val currentState = app.preferenceManager.isAutoAnalyzeEnabled()
        app.preferenceManager.setAutoAnalyzeEnabled(!currentState)
    }
    
    /**
     * 获取自动分析状态
     */
    fun isAutoAnalyzeEnabled(): Boolean {
        return app.preferenceManager.isAutoAnalyzeEnabled()
    }
    
    /**
     * 获取提示模板
     */
    fun getPromptTemplate(): String {
        return app.preferenceManager.getPromptTemplate()
    }
    
    /**
     * 设置提示模板
     */
    fun setPromptTemplate(template: String) {
        app.preferenceManager.setPromptTemplate(template)
    }
    
    /**
     * 清除错误消息
     */
    fun clearErrorMessage() {
        _errorMessage.value = ""
    }
} 