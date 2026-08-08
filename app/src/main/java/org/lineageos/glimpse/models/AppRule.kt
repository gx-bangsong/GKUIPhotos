/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * OPPO 式智能相册：自动识别在外卖/打车/社交等应用中拍摄的照片并生成专属图集
 * 可通过设置添加规则实现任意应用扩展。
 */
data class AppRule(
    val id: String = UUID.randomUUID().toString(),
    val key: String, // stable slug, e.g. "meituan"
    val displayName: String, // e.g. "美团众包"
    val enabled: Boolean = true,
    val pathKeywords: List<String> = emptyList(),
    val softwareKeywords: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("key", key)
        put("displayName", displayName)
        put("enabled", enabled)
        put("pathKeywords", JSONArray(pathKeywords))
        put("softwareKeywords", JSONArray(softwareKeywords))
        put("isBuiltIn", isBuiltIn)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): AppRule? {
            return try {
                val key = obj.optString("key").takeIf { it.isNotBlank() } ?: return null
                val displayName = obj.optString("displayName").takeIf { it.isNotBlank() } ?: key
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val enabled = obj.optBoolean("enabled", true)
                val isBuiltIn = obj.optBoolean("isBuiltIn", false)
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())

                fun readStringList(name: String): List<String> {
                    val arr = obj.optJSONArray(name) ?: return emptyList()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i).trim()
                        if (s.isNotEmpty()) list.add(s)
                    }
                    return list.distinct()
                }

                AppRule(
                    id = id,
                    key = slugify(key),
                    displayName = displayName,
                    enabled = enabled,
                    pathKeywords = readStringList("pathKeywords"),
                    softwareKeywords = readStringList("softwareKeywords"),
                    isBuiltIn = isBuiltIn,
                    createdAt = createdAt
                ).takeIf { it.pathKeywords.isNotEmpty() || it.softwareKeywords.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        fun slugify(input: String): String {
            return input.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9_]+"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .ifBlank { "rule_${UUID.randomUUID().toString().take(6)}" }
        }
    }
}

/**
 * Wrapper for import/export file.
 */
data class AppRuleExport(
    val version: Int = 1,
    val exportTime: Long = System.currentTimeMillis(),
    val rules: List<AppRule>
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("version", version)
        root.put("exportTime", exportTime)
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        root.put("rules", arr)
        return root.toString(2)
    }

    companion object {
        fun fromJson(json: String): AppRuleExport? {
            return try {
                val root = JSONObject(json)
                val version = root.optInt("version", 1)
                val exportTime = root.optLong("exportTime", System.currentTimeMillis())
                val arr = root.optJSONArray("rules") ?: return null
                val rules = mutableListOf<AppRule>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    AppRule.fromJson(obj)?.let { rules.add(it) }
                }
                AppRuleExport(version, exportTime, rules)
            } catch (_: Exception) {
                null
            }
        }
    }
}
