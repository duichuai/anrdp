package com.anrdp.tailscale

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anrdp.data.AppDatabase
import com.anrdp.data.TailscaleConfigEntity
import com.anrdp.databinding.ActivityTailscaleConfigEditBinding
import kotlinx.coroutines.launch

class TailscaleConfigEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONFIG_ID = "config_id"
    }

    private lateinit var binding: ActivityTailscaleConfigEditBinding
    private val db by lazy { AppDatabase.get(this) }
    private var editing: TailscaleConfigEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTailscaleConfigEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val id = intent.getLongExtra(EXTRA_CONFIG_ID, -1L)
        if (id > 0) {
            lifecycleScope.launch {
                db.tailscaleConfigDao().getById(id)?.let {
                    editing = it
                    binding.etName.setText(it.name)
                    binding.etControlURL.setText(it.controlURL)
                    binding.etAuthKey.setText(it.authKey)
                    binding.etHostname.setText(it.hostname)
                }
            }
        }

        binding.btnSave.setOnClickListener { save() }
    }

    private fun save() {
        val name = binding.etName.text.toString().trim()
        val controlURL = binding.etControlURL.text.toString().trim()
        val authKey = binding.etAuthKey.text.toString().trim()

        if (name.isEmpty()) {
            binding.etName.error = "请输入名称"
            return
        }
        if (controlURL.isEmpty()) {
            binding.etControlURL.error = "请输入控制面 URL"
            return
        }
        if (authKey.isEmpty()) {
            binding.etAuthKey.error = "请输入 pre-auth key"
            return
        }

        val old = editing
        val entity = TailscaleConfigEntity(
            id = old?.id ?: 0,
            name = name,
            controlURL = controlURL,
            authKey = authKey,
            hostname = binding.etHostname.text.toString().trim().ifEmpty { "anrdp" },
            stateDir = old?.stateDir ?: "",
            createdAt = old?.createdAt ?: System.currentTimeMillis()
        )

        lifecycleScope.launch {
            if (old == null) db.tailscaleConfigDao().insert(entity)
            else db.tailscaleConfigDao().update(entity)
            Toast.makeText(this@TailscaleConfigEditActivity, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
