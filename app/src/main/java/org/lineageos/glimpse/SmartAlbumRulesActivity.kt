/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.glimpse.models.AppRule
import org.lineageos.glimpse.utils.media.SourceAlbumRulesRepository

class SmartAlbumRulesActivity : AppCompatActivity(R.layout.activity_smart_album_rules) {

    private val repository by lazy { SourceAlbumRulesRepository.getInstance(this) }
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var fabAdd: FloatingActionButton
    private val adapter = RulesAdapter(
        onToggle = { rule, enabled ->
            repository.setEnabled(rule.id, enabled)
        },
        onEdit = { rule -> showEditDialog(rule) },
        onDelete = { rule -> confirmDelete(rule) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        fabAdd = findViewById(R.id.fabAdd)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fabAdd.setOnClickListener { showEditDialog(null) }

        lifecycleScope.launch {
            repository.rulesFlow.collectLatest { rules ->
                adapter.submitList(rules)
                emptyView.isVisible = rules.isEmpty()
            }
        }
    }

    private fun showEditDialog(existing: AppRule?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_rule, null)
        val inputName = view.findViewById<TextInputEditText>(R.id.inputName)
        val inputKey = view.findViewById<TextInputEditText>(R.id.inputKey)
        val inputPath = view.findViewById<TextInputEditText>(R.id.inputPath)
        val inputSw = view.findViewById<TextInputEditText>(R.id.inputSw)

        existing?.let {
            inputName.setText(it.displayName)
            inputKey.setText(it.key)
            inputPath.setText(it.pathKeywords.joinToString(", "))
            inputSw.setText(it.softwareKeywords.joinToString(", "))
            if (it.isBuiltIn) {
                inputKey.isEnabled = false
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) getString(R.string.smart_album_add_rule) else getString(R.string.smart_album_edit_rule))
            .setView(view)
            .setPositiveButton(R.string.smart_album_save) { _, _ ->
                val name = inputName.text?.toString()?.trim().orEmpty()
                var key = inputKey.text?.toString()?.trim().orEmpty()
                val pathStr = inputPath.text?.toString()?.trim().orEmpty()
                val swStr = inputSw.text?.toString()?.trim().orEmpty()

                if (name.isBlank()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val pathKeywords = pathStr.split(",", "，", "\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val swKeywords = swStr.split(",", "，", "\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

                if (pathKeywords.isEmpty() && swKeywords.isEmpty()) {
                    Toast.makeText(this, "至少需要一个关键字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (key.isBlank()) key = AppRule.slugify(name)

                if (existing == null) {
                    val rule = AppRule(
                        key = AppRule.slugify(key),
                        displayName = name,
                        pathKeywords = pathKeywords,
                        softwareKeywords = swKeywords,
                        isBuiltIn = false
                    )
                    val ok = repository.addRule(rule)
                    if (!ok) Toast.makeText(this, "Key 已存在: ${rule.key}", Toast.LENGTH_SHORT).show()
                } else {
                    val updated = existing.copy(
                        key = if (existing.isBuiltIn) existing.key else AppRule.slugify(key),
                        displayName = name,
                        pathKeywords = pathKeywords,
                        softwareKeywords = swKeywords
                    )
                    repository.updateRule(updated)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(rule: AppRule) {
        if (rule.isBuiltIn) {
            Toast.makeText(this, R.string.smart_album_builtin_cannot_delete, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.smart_album_delete)
            .setMessage(getString(R.string.smart_album_delete_confirm) + "\n${rule.displayName}")
            .setPositiveButton(R.string.smart_album_delete) { _, _ ->
                repository.deleteRule(rule.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class RulesAdapter(
        private val onToggle: (AppRule, Boolean) -> Unit,
        private val onEdit: (AppRule) -> Unit,
        private val onDelete: (AppRule) -> Unit,
    ) : ListAdapter<AppRule, RulesAdapter.VH>(Diff) {

        object Diff : DiffUtil.ItemCallback<AppRule>() {
            override fun areItemsTheSame(old: AppRule, new: AppRule) = old.id == new.id
            override fun areContentsTheSame(old: AppRule, new: AppRule) = old == new
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.ruleName)
            val key: TextView = view.findViewById(R.id.ruleKey)
            val path: TextView = view.findViewById(R.id.rulePathKeywords)
            val sw: TextView = view.findViewById(R.id.ruleSwKeywords)
            val enabled: MaterialSwitch = view.findViewById(R.id.ruleEnabled)
            val btnEdit: MaterialButton = view.findViewById(R.id.btnEdit)
            val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_smart_rule, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val rule = getItem(position)
            holder.name.text = rule.displayName + if (rule.isBuiltIn) " • 内置" else ""
            holder.key.text = rule.key
            holder.path.text = "Path: ${rule.pathKeywords.joinToString(", ").ifEmpty { "-" }}"
            holder.sw.text = "EXIF: ${rule.softwareKeywords.joinToString(", ").ifEmpty { "-" }}"
            holder.enabled.setOnCheckedChangeListener(null)
            holder.enabled.isChecked = rule.enabled
            holder.enabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(rule, isChecked)
            }
            holder.btnEdit.setOnClickListener { onEdit(rule) }
            holder.btnDelete.setOnClickListener { onDelete(rule) }
            holder.btnDelete.isEnabled = !rule.isBuiltIn
            holder.btnDelete.alpha = if (rule.isBuiltIn) 0.5f else 1f
        }
    }
}
