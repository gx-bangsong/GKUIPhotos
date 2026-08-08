/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lineageos.glimpse.models.AppRule
import org.lineageos.glimpse.models.AppRuleExport
import org.json.JSONArray

/**
 * 智能相册规则仓库，支持增删改查、导入导出、内置规则管理。
 * 完全离线，存储在 SharedPreferences。
 */
class SourceAlbumRulesRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private val _rulesFlow = MutableStateFlow(loadAll())
    val rulesFlow = _rulesFlow.asStateFlow()

    companion object {
        private const val KEY_RULES_JSON = "smart_album_rules_json"
        private const val KEY_RULES_ENABLED = "smart_album_enabled"
        private const val KEY_EXIF_SCAN_LIMIT = "smart_album_exif_scan_limit"

        @Volatile
        private var INSTANCE: SourceAlbumRulesRepository? = null

        fun getInstance(context: Context): SourceAlbumRulesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SourceAlbumRulesRepository(context).also { INSTANCE = it }
            }
        }

        // 内置规则，来自 OPPO 式外卖图集，扩展到通用应用
        val BUILT_IN_RULES: List<AppRule> = listOf(
            AppRule(
                key = "meituan",
                displayName = "美团众包",
                pathKeywords = listOf("Meituan", "meituan", "美团", "CrowdsourcingTakeout"),
                softwareKeywords = listOf("Meituan", "美团"),
                isBuiltIn = true
            ),
            AppRule(
                key = "eleme_fengniao",
                displayName = "蜂鸟即配",
                pathKeywords = listOf("Fengniao", "fengniao", "蜂鸟", "ElemeFengniao"),
                softwareKeywords = listOf("Fengniao", "蜂鸟", "Eleme"),
                isBuiltIn = true
            ),
            AppRule(
                key = "sf_express",
                displayName = "顺丰同城",
                pathKeywords = listOf("SFExpress", "顺丰", "SF_SameCity", "SFExpressCourier"),
                softwareKeywords = listOf("SFExpress", "顺丰"),
                isBuiltIn = true
            ),
            AppRule(
                key = "dada",
                displayName = "达达快送",
                pathKeywords = listOf("Dada", "达达", "jd_dada"),
                softwareKeywords = listOf("Dada", "达达"),
                isBuiltIn = true
            ),
            AppRule(
                key = "taobao",
                displayName = "淘宝",
                pathKeywords = listOf("Taobao", "taobao", "淘宝", "com.taobao"),
                softwareKeywords = listOf("Taobao", "淘宝"),
                isBuiltIn = true
            ),
            AppRule(
                key = "wechat",
                displayName = "微信",
                pathKeywords = listOf("com.tencent.mm", "Tencent/MicroMsg", "WeiXin"),
                softwareKeywords = listOf("WeChat", "微信"),
                isBuiltIn = true
            ),
        )
    }

    fun isSmartAlbumEnabled(): Boolean = prefs.getBoolean(KEY_RULES_ENABLED, true)

    fun setSmartAlbumEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RULES_ENABLED, enabled).apply()
    }

    fun getExifScanLimit(): Int = prefs.getInt(KEY_EXIF_SCAN_LIMIT, 60)

    fun setExifScanLimit(limit: Int) {
        prefs.edit().putInt(KEY_EXIF_SCAN_LIMIT, limit.coerceIn(10, 200)).apply()
    }

    fun getAllRules(): List<AppRule> = _rulesFlow.value

    fun getEnabledRules(): List<AppRule> = _rulesFlow.value.filter { it.enabled }

    private fun loadAll(): List<AppRule> {
        val json = prefs.getString(KEY_RULES_JSON, null)
        if (json.isNullOrBlank()) {
            // 首次启动，写入内置规则
            return BUILT_IN_RULES
        }
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<AppRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                org.lineageos.glimpse.models.AppRule.fromJson(obj)?.let { list.add(it) }
            }
            if (list.isEmpty()) BUILT_IN_RULES else mergeWithBuiltIn(list)
        } catch (_: Exception) {
            BUILT_IN_RULES
        }
    }

    /**
     * 确保内置规则始终存在（可被禁用，不可被删除），合并用户自定义规则。
     */
    private fun mergeWithBuiltIn(userRules: List<AppRule>): List<AppRule> {
        val byKey = userRules.associateBy { it.key }.toMutableMap()
        // 内置规则如果不在用户列表中，加入；如果已存在，保留用户的 enabled 状态但更新默认关键字？这里保留用户版本。
        for (builtIn in BUILT_IN_RULES) {
            if (!byKey.containsKey(builtIn.key)) {
                byKey[builtIn.key] = builtIn
            } else {
                // 若用户把内置规则标记为非内置，纠正
                val existing = byKey[builtIn.key]!!
                if (!existing.isBuiltIn) {
                    byKey[builtIn.key] = existing.copy(isBuiltIn = true)
                }
            }
        }
        return byKey.values.sortedWith(compareBy({ !it.isBuiltIn }, { it.displayName }))
    }

    private fun persist(rules: List<AppRule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_RULES_JSON, arr.toString()).apply()
        _rulesFlow.value = rules
    }

    fun addRule(rule: AppRule): Boolean {
        val current = getAllRules().toMutableList()
        if (current.any { it.key == rule.key }) return false
        current.add(rule)
        persist(current.sortedWith(compareBy({ !it.isBuiltIn }, { it.displayName })))
        return true
    }

    fun updateRule(updated: AppRule): Boolean {
        val current = getAllRules().toMutableList()
        val idx = current.indexOfFirst { it.id == updated.id || it.key == updated.key }
        if (idx == -1) return false
        // 内置规则不允许改 key 为空，保留 isBuiltIn
        val old = current[idx]
        val merged = updated.copy(
            isBuiltIn = old.isBuiltIn,
            key = if (old.isBuiltIn) old.key else AppRule.slugify(updated.key)
        )
        current[idx] = merged
        persist(current)
        return true
    }

    fun deleteRule(ruleIdOrKey: String): Boolean {
        val current = getAllRules().toMutableList()
        val target = current.firstOrNull { it.id == ruleIdOrKey || it.key == ruleIdOrKey } ?: return false
        if (target.isBuiltIn) return false // 内置不可删，只能禁用
        current.remove(target)
        persist(current)
        return true
    }

    fun setEnabled(ruleIdOrKey: String, enabled: Boolean): Boolean {
        val current = getAllRules().toMutableList()
        val idx = current.indexOfFirst { it.id == ruleIdOrKey || it.key == ruleIdOrKey }
        if (idx == -1) return false
        current[idx] = current[idx].copy(enabled = enabled)
        persist(current)
        return true
    }

    fun resetToBuiltIn() {
        persist(BUILT_IN_RULES)
    }

    fun exportJson(): String {
        val export = AppRuleExport(rules = getAllRules())
        return export.toJson()
    }

    /**
     * 导入规则，策略：
     * - overwrite: 完全替换为导入文件内容（仍会合并内置）
     * - merge: 合并，key 冲突时用导入的覆盖（内置的 isBuiltIn 保留）
     */
    enum class ImportMode { OVERWRITE, MERGE }

    fun importJson(json: String, mode: ImportMode): Result<Int> {
        val parsed = AppRuleExport.fromJson(json)
            ?: return Result.failure(IllegalArgumentException("Invalid JSON format"))
        val imported = parsed.rules
        if (imported.isEmpty()) return Result.failure(IllegalArgumentException("No valid rules"))

        return try {
            when (mode) {
                ImportMode.OVERWRITE -> {
                    // 完全覆盖，但仍保证内置存在且 isBuiltIn 正确
                    val merged = mergeWithBuiltIn(imported)
                    persist(merged)
                    Result.success(merged.size)
                }
                ImportMode.MERGE -> {
                    val currentMap = getAllRules().associateBy { it.key }.toMutableMap()
                    for (rule in imported) {
                        val existing = currentMap[rule.key]
                        if (existing != null && existing.isBuiltIn) {
                            // 内置：保留 isBuiltIn，更新显示名和关键字，保留 enabled 状态除非导入显式指定
                            currentMap[rule.key] = existing.copy(
                                displayName = rule.displayName,
                                pathKeywords = rule.pathKeywords,
                                softwareKeywords = rule.softwareKeywords,
                                // enabled 仍可被导入覆盖
                                enabled = rule.enabled
                            )
                        } else {
                            currentMap[rule.key] = rule.copy(isBuiltIn = existing?.isBuiltIn ?: false)
                        }
                    }
                    val merged = currentMap.values.toList().sortedWith(compareBy({ !it.isBuiltIn }, { it.displayName }))
                    persist(merged)
                    Result.success(imported.size)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
