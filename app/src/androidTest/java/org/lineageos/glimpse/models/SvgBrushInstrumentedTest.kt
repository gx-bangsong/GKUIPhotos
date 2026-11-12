/* Instrumented local SVG brush import, security and vector rendering tests.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.models

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SvgBrushInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun candidateOnly() = assumeTrue("SVG brushes are candidate-only",
        InstrumentationRegistry.getArguments().getString("uiVersion") != "baseline")

    private fun importSvg(contents: String): CustomBrush? {
        val file = File.createTempFile("brush-test-", ".svg", context.cacheDir)
        return try {
            file.writeText(contents)
            CustomEditorAssetManager.importCustomBrush(context, Uri.fromFile(file), "test")
        } finally { file.delete() }
    }

    @Test fun importsVectorAndDrawsAtMultipleSizes() {
        candidateOnly()
        val brush = requireNotNull(importSvg("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><path d="M0 0 L100 0 L100 100 L0 100 Z" fill="#ff0000"/></svg>"""))
        try {
            assertTrue(brush.tipFile.endsWith(".svg"))
            assertEquals(BrushColorMode.ORIGINAL, brush.colorMode)
            val vector = requireNotNull(brush.javaClass.getMethod("getTipVector").invoke(brush))
            val draw = vector.javaClass.getMethod("draw", Canvas::class.java, java.lang.Float.TYPE,
                java.lang.Integer.TYPE, java.lang.Integer.TYPE, BrushColorMode::class.java)
            for (size in listOf(24, 192)) {
                val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(output)
                    canvas.translate(size / 2f, size / 2f)
                    draw.invoke(vector, canvas, size.toFloat(), Color.BLUE, 100, BrushColorMode.ORIGINAL)
                    assertEquals(Color.RED, output.getPixel(size / 2, size / 2))
                    assertTrue(Color.alpha(output.getPixel(1, size / 2)) > 0)
                } finally { output.recycle() }
            }
            assertTrue(File(brush.tipFile).readText().contains("<svg"))
        } finally { CustomEditorAssetManager.deleteCustomBrush(context, brush.id) }
    }

    @Test fun rejectsUntrustedAndOverComplexDocuments() {
        candidateOnly()
        val cases = listOf(
            "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><svg viewBox='0 0 1 1'><path d='&x;'/></svg>",
            "<svg viewBox='0 0 1 1'><script>1</script><path d='M0 0 L1 1'/></svg>",
            "<svg viewBox='0 0 1 1'><image href='https://example.invalid/x'/></svg>",
            "<svg viewBox='0 0 1000000000 1'><path d='M0 0 L1 1'/></svg>",
            "<svg viewBox='0 0 1 1'>" + "<path d='M0 0 L1 1'/>".repeat(65) + "</svg>",
            "<svg viewBox='0 0 1 1'><path d='M0 0 " + "L1 1 ".repeat(3000) + "'/></svg>",
            "<svg viewBox='0 0 1 1'><path d='M0 0 L1 1' fill='url(https://example.invalid/x)'/></svg>",
            "<svg viewBox='0 0 1 1'><path d='M0 0' transform='scale(2)'/></svg>",
            "<svg viewBox='0 0 1 1'>" + " ".repeat(256 * 1024) + "</svg>",
        )
        cases.forEach { document ->
            val brush = importSvg(document)
            try { assertNull("Unsafe SVG accepted", brush) }
            finally { if (brush != null) CustomEditorAssetManager.deleteCustomBrush(context, brush.id) }
        }
    }
}
