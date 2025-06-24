package com.readassist.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.readassist.R
import com.readassist.utils.DeviceUtils
import com.readassist.utils.PreferenceManager
import com.readassist.utils.DeviceScreenshotManager

/**
 * 通用设备截屏目录设置界面
 * 支持掌阅、Supernote和通用Android设备
 */
class DeviceSetupActivity : AppCompatActivity() {
    
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var deviceScreenshotManager: DeviceScreenshotManager
    
    private lateinit var deviceInfoText: TextView
    private lateinit var configRecyclerView: RecyclerView
    private lateinit var skipButton: Button
    private lateinit var configAdapter: DeviceConfigAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_setup)
        
        preferenceManager = PreferenceManager(this)
        deviceScreenshotManager = DeviceScreenshotManager(this, preferenceManager)
        
        initViews()
        setupRecyclerView()
        updateUI()
    }
    
    private fun initViews() {
        deviceInfoText = findViewById(R.id.tv_device_info)
        configRecyclerView = findViewById(R.id.rv_config_list)
        skipButton = findViewById(R.id.btn_skip)
        
        skipButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
    }
    
    private fun setupRecyclerView() {
        configAdapter = DeviceConfigAdapter(
            configs = deviceScreenshotManager.getScreenshotDirectoryConfigs(),
            onSetupClick = { config -> 
                startSAFSetup(config)
            },
            onClearClick = { config -> 
                clearSAFSetup(config)
            }
        )
        
        configRecyclerView.layoutManager = LinearLayoutManager(this)
        configRecyclerView.adapter = configAdapter
    }
    
    private fun updateUI() {
        val currentDeviceType = DeviceUtils.getDeviceType()
        val currentConfig = deviceScreenshotManager.getCurrentDeviceConfig()
        
        val deviceInfo = """
            📱 设备信息：
            制造商：${android.os.Build.MANUFACTURER}
            型号：${android.os.Build.MODEL}
            检测设备类型：${currentConfig.displayName}
            
            💡 使用说明：
            • 配置截屏目录权限可以实现自动弹窗功能
            • 当您在设备上截屏时，应用会自动弹出对话窗口
            • 建议配置当前设备对应的截屏目录权限
            • 其他设备类型的配置是可选的
            
            ${deviceScreenshotManager.getStatusInfo()}
        """.trimIndent()
        
        deviceInfoText.text = deviceInfo
        
        // 刷新适配器
        configAdapter.updateConfigs(deviceScreenshotManager.getScreenshotDirectoryConfigs())
    }
    
    private fun startSAFSetup(config: DeviceScreenshotManager.ScreenshotDirectoryConfig) {
        deviceScreenshotManager.requestDirectoryAccess(this, config)
    }
    
    private fun clearSAFSetup(config: DeviceScreenshotManager.ScreenshotDirectoryConfig) {
        deviceScreenshotManager.clearDirectoryAccess(config)
        updateUI()
        Toast.makeText(this, "已清除 ${config.displayName} 的配置", Toast.LENGTH_SHORT).show()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (deviceScreenshotManager.handleDirectoryAccessResult(requestCode, resultCode, data)) {
            updateUI()
            Toast.makeText(this, "✅ 目录访问权限配置成功！", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "❌ 配置失败或已取消", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 设备配置适配器
 */
class DeviceConfigAdapter(
    private var configs: List<DeviceScreenshotManager.ScreenshotDirectoryConfig>,
    private val onSetupClick: (DeviceScreenshotManager.ScreenshotDirectoryConfig) -> Unit,
    private val onClearClick: (DeviceScreenshotManager.ScreenshotDirectoryConfig) -> Unit
) : RecyclerView.Adapter<DeviceConfigAdapter.ViewHolder>() {
    
    private lateinit var deviceScreenshotManager: DeviceScreenshotManager
    
    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val deviceName: TextView = view.findViewById(R.id.tv_device_name)
        val devicePath: TextView = view.findViewById(R.id.tv_device_path)
        val deviceDescription: TextView = view.findViewById(R.id.tv_device_description)
        val statusIndicator: TextView = view.findViewById(R.id.tv_status_indicator)
        val setupButton: Button = view.findViewById(R.id.btn_setup)
        val clearButton: Button = view.findViewById(R.id.btn_clear)
    }
    
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        // 需要创建对应的layout文件
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device_config, parent, false)
        
        // 初始化deviceScreenshotManager
        if (!::deviceScreenshotManager.isInitialized) {
            val preferenceManager = PreferenceManager(parent.context)
            deviceScreenshotManager = DeviceScreenshotManager(parent.context, preferenceManager)
        }
        
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = configs[position]
        val hasAccess = deviceScreenshotManager.hasDirectoryAccess(config)
        val isCurrentDevice = DeviceUtils.getDeviceType() == config.deviceType
        
        holder.deviceName.text = "${config.displayName}${if (isCurrentDevice) " (当前设备)" else ""}"
        holder.devicePath.text = "路径: ${config.systemPath}"
        holder.deviceDescription.text = config.description
        
        if (hasAccess) {
            holder.statusIndicator.text = "✅ 已配置"
            holder.statusIndicator.setTextColor(0xFF4CAF50.toInt())
            holder.setupButton.text = "重新配置"
            holder.clearButton.isEnabled = true
        } else {
            holder.statusIndicator.text = "❌ 未配置"
            holder.statusIndicator.setTextColor(0xFFF44336.toInt())
            holder.setupButton.text = "配置权限"
            holder.clearButton.isEnabled = false
        }
        
        // 突出显示当前设备
        if (isCurrentDevice) {
            holder.itemView.setBackgroundColor(0xFFF0F8FF.toInt()) // 淡蓝色背景
        } else {
            holder.itemView.setBackgroundColor(0xFFFFFFFF.toInt()) // 白色背景
        }
        
        holder.setupButton.setOnClickListener {
            onSetupClick(config)
        }
        
        holder.clearButton.setOnClickListener {
            onClearClick(config)
        }
    }
    
    override fun getItemCount(): Int = configs.size
    
    fun updateConfigs(newConfigs: List<DeviceScreenshotManager.ScreenshotDirectoryConfig>) {
        configs = newConfigs
        notifyDataSetChanged()
    }
} 