/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.CallSuper
import androidx.annotation.Px
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.lineageos.glimpse.ext.setOffset
import org.lineageos.glimpse.utils.media.SourceAlbumRulesRepository
import kotlin.reflect.safeCast

class SettingsActivity : AppCompatActivity(R.layout.activity_settings) {
    private val appBarLayout by lazy { findViewById<AppBarLayout>(R.id.appBarLayout) }
    private val coordinatorLayout by lazy { findViewById<CoordinatorLayout>(R.id.coordinatorLayout) }
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, RootSettingsFragment())
                .commit()
        }

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }

        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    abstract class SettingsFragment(
        @XmlRes private val preferencesResId: Int,
    ) : PreferenceFragmentCompat() {
        private val settingsActivity
            get() = SettingsActivity::class.safeCast(activity)

        @Px
        private var appBarOffset = -1

        private val offsetChangedListener = AppBarLayout.OnOffsetChangedListener { _, i ->
            appBarOffset = -i
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            settingsActivity?.let { settingsActivity ->
                val appBarLayout = settingsActivity.appBarLayout

                if (appBarOffset != -1) {
                    appBarLayout.setOffset(appBarOffset, settingsActivity.coordinatorLayout)
                } else {
                    appBarLayout.setExpanded(true, false)
                }

                appBarLayout.setLiftOnScrollTargetView(listView)

                appBarLayout.addOnOffsetChangedListener(offsetChangedListener)
            }
        }

        override fun onDestroyView() {
            settingsActivity?.appBarLayout?.apply {
                removeOnOffsetChangedListener(offsetChangedListener)

                setLiftOnScrollTargetView(null)
            }

            super.onDestroyView()
        }

        @CallSuper
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(preferencesResId, rootKey)
        }

        @CallSuper
        override fun onCreateRecyclerView(
            inflater: LayoutInflater,
            parent: ViewGroup,
            savedInstanceState: Bundle?
        ) = super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false

            ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

                updatePadding(
                    bottom = insets.bottom,
                    left = insets.left,
                    right = insets.right,
                )

                windowInsets
            }
        }
    }

    class RootSettingsFragment : SettingsFragment(R.xml.root_preferences) {

        private val repository by lazy { SourceAlbumRulesRepository.getInstance(requireContext()) }

        private val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(repository.exportJson().toByteArray())
                }
                Toast.makeText(
                    requireContext(),
                    requireContext().getString(R.string.smart_album_export_success, uri.toString()),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        private val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                val json = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("Empty file")

                // 选择导入模式
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.smart_album_import_mode_title)
                    .setItems(
                        arrayOf(
                            getString(R.string.smart_album_import_mode_merge),
                            getString(R.string.smart_album_import_mode_overwrite)
                        )
                    ) { _, which ->
                        val mode = if (which == 0) SourceAlbumRulesRepository.ImportMode.MERGE
                        else SourceAlbumRulesRepository.ImportMode.OVERWRITE
                        val result = repository.importJson(json, mode)
                        result.onSuccess { count ->
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.smart_album_import_success, count),
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure { err ->
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.smart_album_import_failed, err.message ?: "unknown"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .show()

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)

            findPreference<Preference>("smart_album_manage_rules")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), SmartAlbumRulesActivity::class.java))
                true
            }

            findPreference<Preference>("smart_album_import")?.setOnPreferenceClickListener {
                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                true
            }

            findPreference<Preference>("smart_album_export")?.setOnPreferenceClickListener {
                val fileName = "glimpse-rules-${System.currentTimeMillis()}.json"
                exportLauncher.launch(fileName)
                true
            }

            findPreference<Preference>("smart_album_reset")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.smart_album_reset_title)
                    .setMessage("重置为内置的外卖/社交规则？自定义规则将被清除。")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        repository.resetToBuiltIn()
                        Toast.makeText(requireContext(), "已重置", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
    }
}
