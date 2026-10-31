/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models.idphoto

import androidx.annotation.StringRes
import kotlin.math.roundToInt

enum class PresetGroup {
    COMMON,     // 常用寸照
    OFFICIAL,   // 官方证件
    VISA,       // 国际签证
    EXAM,       // 考试与职业资格认证
}

data class IdPhotoPreset(
    val id: String,
    @StringRes val nameRes: Int,
    val widthMm: Int,
    val heightMm: Int,
    /** 输出分辨率：必须逐项精准定义，严禁全局写死 300（常规 300，身份证 350，电子驾驶证 600） */
    val dpi: Int = 300,
    val group: PresetGroup,
    val specSource: String,
    val description: String = "",
    val customTargetWidthPx: Int? = null,
    val customTargetHeightPx: Int? = null,
) {
    /** 派生高宽比，裁剪框唯一数据源，绝不显式存储 */
    val aspectRatio: Float
        get() = widthMm.toFloat() / heightMm.toFloat()

    /** 派生目标输出像素宽度（严格依据物理毫米与该预设特有的标准 DPI 计算，或显式指定的 customTarget） */
    val targetWidthPx: Int
        get() = customTargetWidthPx ?: (widthMm * dpi / 25.4f).roundToInt()

    /** 派生目标输出像素高度（严格依据物理毫米与该预设特有的标准 DPI 计算，或显式指定的 customTarget） */
    val targetHeightPx: Int
        get() = customTargetHeightPx ?: (heightMm * dpi / 25.4f).roundToInt()
}
