package com.peerrdp.edit

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.peerrdp.R
import com.peerrdp.data.AppDatabase
import com.peerrdp.data.ConnectionEntity
import com.peerrdp.data.TailscaleConfigEntity
import com.peerrdp.databinding.ActivityConnectionEditBinding
import kotlinx.coroutines.launch

class ConnectionEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        /** 以该连接为模板复制新建（不覆盖原连接） */
        const val EXTRA_COPY_FROM_ID = "copy_from_id"
    }

    private lateinit var binding: ActivityConnectionEditBinding
    private val db by lazy { AppDatabase.get(this) }
    private var editing: ConnectionEntity? = null
    private lateinit var resolutionLabels: Array<String>
    private lateinit var resolutionValues: Array<String>
    private var tailscaleConfigs: List<TailscaleConfigEntity> = emptyList()
    private var selectedTailscaleId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        resolutionLabels = resources.getStringArray(R.array.resolution_labels)
        resolutionValues = resources.getStringArray(R.array.resolution_values)
        binding.actResolution.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, resolutionLabels)
        )
        selectResolution(ConnectionEntity.RESOLUTION_ADAPTIVE)

        binding.btnSave.setOnClickListener { save() }

        // 顺序加载：先取 Tailscale 配置，再取连接回显，避免异步竞态覆盖 selectedTailscaleId
        val id = intent.getLongExtra(EXTRA_CONNECTION_ID, -1L)
        val copyFromId = intent.getLongExtra(EXTRA_COPY_FROM_ID, -1L)
        lifecycleScope.launch {
            val configs = db.tailscaleConfigDao().getAll()
            tailscaleConfigs = configs
            setupTailscaleSpinner(configs)

            val entity = when {
                copyFromId > 0 -> db.connectionDao().getById(copyFromId)
                id > 0 -> db.connectionDao().getById(id)
                else -> null
            }
            if (entity != null) fillForm(entity, copy = copyFromId > 0)
        }
    }

    /** 设置 Tailscale 配置下拉框；在协程内同步设置选中项，避免回调覆盖 selectedTailscaleId */
    private fun setupTailscaleSpinner(configs: List<TailscaleConfigEntity>) {
        val items = mutableListOf("不使用 Tailscale（直连）")
        items.addAll(configs.map { "${it.name} (${it.controlURL})" })
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            items
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinTailscale.adapter = adapter

        // 先设置 listener，再 setSelection，确保回调正常更新 selectedTailscaleId
        binding.spinTailscale.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedTailscaleId = if (pos == 0) 0 else configs[pos - 1].id
                }

                override fun onNothingSelected(p: AdapterView<*>?) {
                    selectedTailscaleId = 0
                }
            }
        binding.spinTailscale.setSelection(0)
    }

    /** 回显连接配置；copy=true 时作为复制模板，保存时新建而非覆盖 */
    private fun fillForm(entity: ConnectionEntity, copy: Boolean) {
        if (!copy) editing = entity
        binding.etName.setText(
            if (copy) {
                getString(R.string.copy_name, entity.name.ifEmpty { entity.host })
            } else {
                entity.name
            }
        )
        binding.etHost.setText(entity.host)
        binding.etPort.setText(entity.port.toString())
        binding.etUser.setText(entity.username)
        binding.etPassword.setText(entity.password)
        binding.etDomain.setText(entity.domain)
        selectResolution(entity.resolution)

        // 回显 Tailscale 选中项：根据 tailscaleConfigId 找到对应位置
        val idx = tailscaleConfigs.indexOfFirst { it.id == entity.tailscaleConfigId }
        val pos = if (idx >= 0) idx + 1 else 0
        binding.spinTailscale.setSelection(pos)
        selectedTailscaleId = entity.tailscaleConfigId

        supportActionBar?.setTitle(
            if (copy) R.string.title_copy_connection else R.string.title_edit_connection
        )
    }

    private fun save() {
        val host = binding.etHost.text.toString().trim()
        val portStr = binding.etPort.text.toString().trim()
        val port = portStr.toIntOrNull()

        if (host.isEmpty()) {
            binding.etHost.error = getString(R.string.error_host_required)
            return
        }
        if (port == null || port !in 1..65535) {
            binding.etPort.error = getString(R.string.error_port_invalid)
            return
        }

        val old = editing
        val resolutionIndex = resolutionLabels.indexOf(binding.actResolution.text.toString())
        val entity = ConnectionEntity(
            id = old?.id ?: 0,
            name = binding.etName.text.toString().trim().ifEmpty { host },
            host = host,
            port = port,
            username = binding.etUser.text.toString().trim(),
            password = binding.etPassword.text.toString(),
            domain = binding.etDomain.text.toString().trim(),
            resolution = resolutionValues.getOrNull(resolutionIndex)
                ?: ConnectionEntity.RESOLUTION_ADAPTIVE,
            tailscaleConfigId = selectedTailscaleId,
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
            lastUsedAt = old?.lastUsedAt ?: 0
        )

        lifecycleScope.launch {
            if (old == null) db.connectionDao().insert(entity) else db.connectionDao().update(entity)
            Toast.makeText(this@ConnectionEditActivity, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** 按存储值回显分辨率的显示文案 */
    private fun selectResolution(value: String) {
        val index = resolutionValues.indexOfFirst { it == value }
            .takeIf { it >= 0 } ?: 0
        binding.actResolution.setText(resolutionLabels[index], false)
    }
}
