/* Temporary runtime measurements for the pre-2a markup control inventory.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.uiautomator.UiDevice
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import java.util.Locale

/** Read existing controls only; unavailable controls are observations, not assertions. */
internal class MarkupControlMeasurement(
    private val activity: Activity,
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
) {
    private val lines = StringBuilder()
    private var count = 0
    private fun message(error: Throwable) = "${error.javaClass.name}: ${(error.message ?: "(no message)").replace('\n', ' ')}"
    private val metrics get() = activity.resources.displayMetrics
    private val density get() = metrics.density
    private fun num(value: Float) = String.format(Locale.US, "%.3f", value)
    private fun dp(value: Float) = num(value / density)
    private fun dim(value: Float) = "${num(value)}px/${dp(value)}dp"

    private fun root(): View = activity.window.decorView
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
    } else emptyList()

    private fun label(name: String): String? {
        val id = activity.resources.getIdentifier(name, "string", activity.packageName)
        return if (id == 0) null else activity.getString(id)
    }

    private fun byText(name: String): View? {
        val text = label(name) ?: return null
        return views(root()).firstOrNull { it is TextView && it.text.toString() == text }
    }

    private fun byDescription(name: String): View? {
        val text = label(name) ?: return null
        return views(root()).firstOrNull { it.contentDescription?.toString() == text }
    }

    private fun byLiteral(text: String): View? = views(root()).firstOrNull {
        (it is TextView && it.text.toString() == text) || it.contentDescription?.toString() == text
    }

    /** Fail the current measurement group if its page cannot be opened. */
    fun select(name: String) {
        try {
            runOnMainWithTimeout(instrumentation, "measurement select $name") {
                var view: View? = byDescription(name) ?: byText(name)
                while (view != null && !view.isClickable) view = view.parent as? View
                if (view == null) throw IllegalStateException("navigation target not found")
                if (!view.performClick()) throw IllegalStateException("navigation click rejected")
            }
            waitForBoundedUiIdle(instrumentation, device, "measurement select $name")
            if (name != "editor_tab_markup") {
                runOnMainWithTimeout(instrumentation, "verify measurement page $name") {
                    val selected = byDescription(name)
                    if (selected?.isSelected != true) throw IllegalStateException("navigation target is not selected")
                }
            }
        } catch (error: Throwable) {
            throw IllegalStateException("$name: ${message(error)}", error)
        }
    }

    private fun options(): View {
        val id = activity.resources.getIdentifier("markupOptionsContent", "id", activity.packageName)
        return activity.findViewById<View>(id) ?: throw IllegalStateException("markup options page unavailable")
    }

    private fun corner(view: View): String {
        val px = when (view) {
            is Chip -> view.chipCornerRadius
            is MaterialCardView -> view.radius
            is MaterialButton -> view.shapeAppearanceModel.topLeftCornerSize.getCornerSize(
                RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()))
            else -> {
                fun radius(drawable: android.graphics.drawable.Drawable?): Float? = when (drawable) {
                    is GradientDrawable -> drawable.cornerRadius
                    is InsetDrawable -> radius(drawable.drawable)
                    is LayerDrawable -> (0 until drawable.numberOfLayers).firstNotNullOfOrNull { radius(drawable.getDrawable(it)) }
                    else -> null
                }
                radius(view.background)
            }
        }
        return px?.let(::dim) ?: "unavailable"
    }

    private fun neighborGaps(view: View): String {
        val location = IntArray(2).also(view::getLocationOnScreen)
        val left = location[0]
        val right = left + view.width
        val top = location[1]
        val bottom = top + view.height
        val candidates = views(options()).filter { other ->
            other !== view && other.isClickable && other.visibility == View.VISIBLE &&
                other.width > 0 && other.height > 0 && (other !is ViewGroup || other is MaterialCardView)
        }.mapNotNull { other ->
            val p = IntArray(2).also(other::getLocationOnScreen)
            if (p[1] >= bottom || p[1] + other.height <= top) null else p[0] to p[0] + other.width
        }
        val before = candidates.filter { it.second <= left }.maxOfOrNull { it.second }
        val after = candidates.filter { it.first >= right }.minOfOrNull { it.first }
        return "gap_before=${before?.let { dim((left - it).toFloat()) } ?: "none"} " +
            "gap_after=${after?.let { dim((it - right).toFloat()) } ?: "none"}"
    }

    private fun describe(view: View?): String {
        if (view == null) return "不存在"
        val text = view as? TextView
        val sp = text?.let {
            if (android.os.Build.VERSION.SDK_INT >= 34) TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, it.textSize, metrics)
            else @Suppress("DEPRECATION") (it.textSize / metrics.scaledDensity)
        }
        val chip = view as? Chip
        return buildString {
            append("class=${view.javaClass.simpleName} width=${dim(view.width.toFloat())} height=${dim(view.height.toFloat())} ")
            append("text_size=${text?.let { num(it.textSize) + "px/" + num(sp!!) + "sp" } ?: "not_applicable"} ")
            append("padding_left=${dim(view.paddingLeft.toFloat())} padding_right=${dim(view.paddingRight.toFloat())} ")
            if (chip != null) append("chip_start=${dim(chip.chipStartPadding)} chip_end=${dim(chip.chipEndPadding)} ")
                .append("text_start=${dim(chip.textStartPadding)} text_end=${dim(chip.textEndPadding)} ")
            else append("chip_start=not_applicable chip_end=not_applicable text_start=not_applicable text_end=not_applicable ")
            append("corner=${corner(view)} ${neighborGaps(view)}")
        }
    }

    fun environment() {
        lines.append("density=${num(density)} density_dpi=${metrics.densityDpi} font_scale=${num(activity.resources.configuration.fontScale)}\n")
    }

    fun record(name: String, resource: String? = null, description: String? = null, literal: String? = null) {
        count++
        try {
            var result = "不存在"
            runOnMainWithTimeout(instrumentation, "measurement $name") {
                val candidates = views(options())
                val text = resource?.let(::label)
                val desc = description?.let(::label)
                val view = candidates.firstOrNull {
                    (text != null && it is TextView && it.text.toString() == text) ||
                        (desc != null && it.contentDescription?.toString() == desc) ||
                        (literal != null && ((it is TextView && it.text.toString() == literal) || it.contentDescription?.toString() == literal))
                }
                result = describe(view)
            }
            lines.append("$name: $result\n")
        } catch (error: Throwable) {
            lines.append("$name: 失败：测量 $name ${message(error)}\n")
        }
    }

    fun failed(names: List<String>, step: String, error: Throwable) {
        names.forEach { name -> count++; lines.append("$name: 失败：$step ${message(error)}\n") }
    }
    fun output(): String = lines.toString() + "control_total=$count\n"
}
