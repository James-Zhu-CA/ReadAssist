package com.readassist.service

/**
 * 聊天项数据类
 */
data class ChatItem(
    val userMessage: String,
    val aiMessage: String,
    val isUserMessage: Boolean,
    val isLoading: Boolean = false,
    val isError: Boolean = false
) 