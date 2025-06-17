package com.readassist.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.readassist.R
import com.readassist.utils.DeviceUtils
import com.readassist.utils.PreferenceManager
import com.readassist.utils.StorageAccessManager

/**
 * 掌阅设备SAF设置界面
 */
class IReaderSetupActivity : AppCompatActivity() {
    
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var storageAccessManager: StorageAccessManager
    
    private lateinit var statusText: TextView
    private lateinit var setupButton: Button
    private lateinit var skipButton: Button
    private lateinit var clearButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ireader_setup)
        
        preferenceManager = PreferenceManager(this)
        storageAccessManager = StorageAccessManager(this, preferenceManager)
        
        initViews()
        updateUI()
    }
    
    private fun initViews() {
        statusText = findViewById(R.id.tv_status)
        setupButton = findViewById(R.id.btn_setup)
        skipButton = findViewById(R.id.btn_skip)
        clearButton = findViewById(R.id.btn_clear)
        
        setupButton.setOnClickListener {
            startSAFSetup()
        }
        
        skipButton.setOnClickListener {
            // 返回主界面
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
        
        clearButton.setOnClickListener {
            clearSAFSetup()
        }
    }
    
    private fun updateUI() {
        val deviceInfo = """
            📱 设备信息：
            制造商：${android.os.Build.MANUFACTURER}
            型号：${android.os.Build.MODEL}
            设备类型：${if (DeviceUtils.isIReaderDevice()) "掌阅设备" else "其他设备"}
            截屏目录：/storage/emulated/0/iReader/saveImage
            建议把掌阅设备的按键设置为长按截屏，使用更方便！
            
            ${storageAccessManager.getStatusInfo()}
        """.trimIndent()
        
        statusText.text = deviceInfo
        
        val hasAccess = storageAccessManager.hasIReaderDirectoryAccess()
        setupButton.text = if (hasAccess) "重新配置目录" else "配置截屏目录"
        clearButton.isEnabled = hasAccess
    }
    
    private fun startSAFSetup() {
        storageAccessManager.requestIReaderDirectoryAccess(this)
    }
    
    private fun clearSAFSetup() {
        storageAccessManager.clearIReaderDirectoryAccess()
        updateUI()
        Toast.makeText(this, "已清除配置", Toast.LENGTH_SHORT).show()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (storageAccessManager.handleDirectoryAccessResult(requestCode, resultCode, data)) {
            updateUI()
            Toast.makeText(this, "✅ 目录访问权限配置成功！", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "❌ 配置失败或已取消", Toast.LENGTH_SHORT).show()
        }
    }
} 