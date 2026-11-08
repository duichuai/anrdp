package com.anrdp

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anrdp.data.AppDatabase
import com.anrdp.data.ConnectionEntity
import com.anrdp.databinding.ActivityMainBinding
import com.anrdp.databinding.ItemConnectionBinding
import com.anrdp.edit.ConnectionEditActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.get(this) }
    private val adapter = ConnectionAdapter(
        onClick = { onConnect(it) },
        onLongClick = { showItemMenu(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, ConnectionEditActivity::class.java))
        }

        lifecycleScope.launch {
            db.connectionDao().observeAll().collect { list ->
                adapter.submitList(list)
                binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onConnect(conn: ConnectionEntity) {
        lifecycleScope.launch { db.connectionDao().touch(conn.id, System.currentTimeMillis()) }
        if (conn.password.isEmpty()) {
            promptPassword(conn)
        } else {
            RdpConnector.connect(this, conn, conn.password)
        }
    }

    /** 未保存密码时，连接前询问（不落库） */
    private fun promptPassword(conn: ConnectionEntity) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.password_hint)
        }
        val margin = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this)
        container.setPadding(margin, margin / 2, margin, 0)
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.enter_password_title, conn.username))
            .setView(container)
            .setPositiveButton(R.string.connect) { _, _ ->
                RdpConnector.connect(this, conn, input.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showItemMenu(conn: ConnectionEntity) {
        val items = arrayOf(
            getString(R.string.copy),
            getString(R.string.edit),
            getString(R.string.delete)
        )
        AlertDialog.Builder(this)
            .setTitle(conn.name.ifEmpty { conn.host })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, ConnectionEditActivity::class.java)
                            .putExtra(ConnectionEditActivity.EXTRA_COPY_FROM_ID, conn.id)
                    )
                    1 -> startActivity(
                        Intent(this, ConnectionEditActivity::class.java)
                            .putExtra(ConnectionEditActivity.EXTRA_CONNECTION_ID, conn.id)
                    )
                    2 -> confirmDelete(conn)
                }
            }
            .show()
    }

    private fun confirmDelete(conn: ConnectionEntity) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, conn.name.ifEmpty { conn.host }))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch { db.connectionDao().delete(conn) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class ConnectionAdapter(
        private val onClick: (ConnectionEntity) -> Unit,
        private val onLongClick: (ConnectionEntity) -> Unit
    ) : ListAdapter<ConnectionEntity, ConnectionAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemConnectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(private val b: ItemConnectionBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: ConnectionEntity) {
                b.tvName.text = item.name.ifEmpty { item.host }
                b.tvAddress.text = "${item.host}:${item.port}"
                b.tvUser.text = item.username
                b.root.setOnClickListener { onClick(item) }
                b.root.setOnLongClickListener { onLongClick(item); true }
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<ConnectionEntity>() {
                override fun areItemsTheSame(a: ConnectionEntity, b: ConnectionEntity) = a.id == b.id
                override fun areContentsTheSame(a: ConnectionEntity, b: ConnectionEntity) = a == b
            }
        }
    }
}
