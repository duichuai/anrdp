package com.anrdp.edit

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anrdp.R
import com.anrdp.data.AppDatabase
import com.anrdp.data.ConnectionEntity
import com.anrdp.databinding.ActivityConnectionEditBinding
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

        val id = intent.getLongExtra(EXTRA_CONNECTION_ID, -1L)
        val copyFromId = intent.getLongExtra(EXTRA_COPY_FROM_ID, -1L)
        when {
            copyFromId > 0 -> lifecycleScope.launch {
                db.connectionDao().getById(copyFromId)?.let { fillForm(it, copy = true) }
            }
            id > 0 -> lifecycleScope.launch {
                db.connectionDao().getById(id)?.let { fillForm(it, copy = false) }
            }
        }

        binding.btnSave.setOnClickListener { save() }
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
