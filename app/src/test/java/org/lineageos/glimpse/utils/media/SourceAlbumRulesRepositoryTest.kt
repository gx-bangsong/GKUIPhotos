/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.lineageos.glimpse.models.AppRule
import org.lineageos.glimpse.models.AppRuleExport

class SourceAlbumRulesRepositoryTest {

    @get:Rule
    val failureDiagnostics = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            val detail = listOfNotNull(error::class.java.simpleName, error.message)
                .joinToString(": ")
                .replace(Regex("[\\r\\n]+"), " ")
                .replace("::", "")
            System.err.println("error: smart-album-test ${description.methodName}: $detail")
        }
    }

    @Test
    fun exportThenOverwriteImportPreservesRules() {
        val source = repository()
        source.setEnabled("meituan", false)
        val custom = customRule("custom", enabled = false)
        assertTrue(source.addRule(custom))
        val expected = snapshot(source)

        val target = repository()
        val result = target.importJson(source.exportJson(), SourceAlbumRulesRepository.ImportMode.OVERWRITE)

        assertTrue(result.isSuccess)
        assertEquals(expected, snapshot(target))
    }

    @Test
    fun mergeKeepsExistingCustomRulesAndAddsImportedRules() {
        val repository = repository()
        val existing = customRule("existing", displayName = "Existing")
        val imported = customRule("imported", displayName = "Imported")
        assertTrue(repository.addRule(existing))

        val result = repository.importJson(
            AppRuleExport(rules = listOf(imported)).toJson(),
            SourceAlbumRulesRepository.ImportMode.MERGE,
        )

        assertTrue(result.isSuccess)
        assertEquals(existing, repository.getAllRules().single { it.key == existing.key })
        assertEquals(imported, repository.getAllRules().single { it.key == imported.key })
    }

    @Test
    fun overwriteReplacesExistingCustomRulesButKeepsBuiltIns() {
        val repository = repository()
        val oldRule = customRule("old_rule")
        val replacement = customRule("replacement")
        assertTrue(repository.addRule(oldRule))

        val result = repository.importJson(
            AppRuleExport(rules = listOf(replacement)).toJson(),
            SourceAlbumRulesRepository.ImportMode.OVERWRITE,
        )

        assertTrue(result.isSuccess)
        assertTrue(repository.getAllRules().none { it.key == oldRule.key })
        assertEquals(replacement, repository.getAllRules().single { it.key == replacement.key })
        SourceAlbumRulesRepository.BUILT_IN_RULES.forEach { builtIn ->
            val restored = repository.getAllRules().singleOrNull { it.key == builtIn.key }
            assertNotNull("built-in rule ${builtIn.key} must survive overwrite", restored)
            assertTrue(restored!!.isBuiltIn)
        }
    }

    @Test
    fun malformedOrIncompleteImportDoesNotChangeExistingRules() {
        val repository = repository()
        assertTrue(repository.addRule(customRule("keep_me")))
        val expected = snapshot(repository)

        val invalidDocuments = listOf(
            "not json",
            "{}",
            "{\"version\":1,\"rules\":[]}",
            "{\"version\":1,\"rules\":[{\"key\":\"missing_keywords\",\"displayName\":\"Broken\"}]}",
        )

        invalidDocuments.forEach { document ->
            val result = repository.importJson(document, SourceAlbumRulesRepository.ImportMode.MERGE)
            assertTrue("document should be rejected: $document", result.isFailure)
            assertEquals(expected, snapshot(repository))
        }
    }

    @Test
    fun builtInRulesCannotBeDeletedOrRemovedByImport() {
        val repository = repository()
        assertFalse(repository.deleteRule("meituan"))
        assertTrue(repository.getAllRules().single { it.key == "meituan" }.isBuiltIn)

        val importedLookalike = customRule("meituan", displayName = "Lookalike")
        val result = repository.importJson(
            AppRuleExport(rules = listOf(importedLookalike)).toJson(),
            SourceAlbumRulesRepository.ImportMode.OVERWRITE,
        )

        assertTrue(result.isSuccess)
        val meituan = repository.getAllRules().single { it.key == "meituan" }
        assertTrue("the built-in identity must remain protected", meituan.isBuiltIn)
        assertFalse(repository.deleteRule(meituan.id))
    }

    private fun repository(): SourceAlbumRulesRepository {
        val context = TestContext(inMemoryPreferences())
        val constructor = SourceAlbumRulesRepository::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
        return constructor.newInstance(context)
    }

    private fun customRule(
        key: String,
        displayName: String = key,
        enabled: Boolean = true,
    ) = AppRule(
        id = "id-$key",
        key = key,
        displayName = displayName,
        enabled = enabled,
        pathKeywords = listOf("path-$key"),
        softwareKeywords = listOf("software-$key"),
        isBuiltIn = false,
        createdAt = 123L,
    )

    private fun snapshot(repository: SourceAlbumRulesRepository): List<RuleSnapshot> =
        repository.getAllRules()
            .sortedBy { it.key }
            .map {
                RuleSnapshot(
                    key = it.key,
                    displayName = it.displayName,
                    enabled = it.enabled,
                    pathKeywords = it.pathKeywords,
                    softwareKeywords = it.softwareKeywords,
                    isBuiltIn = it.isBuiltIn,
                    createdAt = it.createdAt,
                )
            }

    private data class RuleSnapshot(
        val key: String,
        val displayName: String,
        val enabled: Boolean,
        val pathKeywords: List<String>,
        val softwareKeywords: List<String>,
        val isBuiltIn: Boolean,
        val createdAt: Long,
    )

    private class TestContext(
        private val preferences: SharedPreferences,
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = "org.lineageos.glimpse.test"

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences
    }

    @Suppress("UNCHECKED_CAST")
    private fun inMemoryPreferences(): SharedPreferences {
        val values = linkedMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor

        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString", "putBoolean", "putInt", "putLong", "putFloat" -> {
                    values[args!![0] as String] = args[1]
                    editor
                }
                "putStringSet" -> {
                    values[args!![0] as String] = (args[1] as? Set<String>)?.toSet()
                    editor
                }
                "remove" -> {
                    values.remove(args!![0] as String)
                    editor
                }
                "clear" -> {
                    values.clear()
                    editor
                }
                "apply" -> Unit
                "commit" -> true
                else -> defaultValue(method.returnType)
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> HashMap(values)
                "getString" -> values[args!![0] as String] as? String ?: args[1] as? String
                "getStringSet" -> (values[args!![0] as String] as? Set<String>)?.toMutableSet()
                    ?: (args[1] as? Set<String>)?.toMutableSet()
                "getBoolean" -> values[args!![0] as String] as? Boolean ?: args[1] as Boolean
                "getInt" -> values[args!![0] as String] as? Int ?: args[1] as Int
                "getLong" -> values[args!![0] as String] as? Long ?: args[1] as Long
                "getFloat" -> values[args!![0] as String] as? Float ?: args[1] as Float
                "contains" -> values.containsKey(args!![0] as String)
                "edit" -> editor
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> Unit
                else -> defaultValue(method.returnType)
            }
        } as SharedPreferences
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
}
