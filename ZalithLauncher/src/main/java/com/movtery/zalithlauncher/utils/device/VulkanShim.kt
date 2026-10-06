/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.utils.device

object VulkanShim {
    const val EXT_VERTEX_ATTRIBUTE_DIVISOR = "VK_EXT_vertex_attribute_divisor"
    const val KHR_VERTEX_ATTRIBUTE_DIVISOR = "VK_KHR_vertex_attribute_divisor"
    const val FEATURE_FILL_MODE_NON_SOLID = "fillModeNonSolid"

    /** 当前驱动状态下是否应经 vkshim 包装加载（divisor 合成或 fillModeNonSolid 模拟任一成立），启动组装环境变量时读取 */
    @Volatile
    var needsVulkanShim: Boolean = false
        private set

    /**
     * 按检测能力判定驱动缺口：缺失 EXT divisor 但提供 KHR 扩展（可经 shim 以 KHR 合成补报），
     * 或核心能力位 fillModeNonSolid 缺失（可经 shim 降级模拟，线框渲染退化为实心）。
     * @return 第一项为 needsDivisorShim，第二项为 needsFillModeEmulation
     */
    fun evaluate(capabilities: VulkanCapabilities?): Pair<Boolean, Boolean> {
        val caps = capabilities?.takeIf { !it.usedCustomDriver } ?: return false to false
        val needsDivisorShim = EXT_VERTEX_ATTRIBUTE_DIVISOR !in caps.extensions &&
                KHR_VERTEX_ATTRIBUTE_DIVISOR in caps.extensions
        val needsFillModeEmulation = caps.features[FEATURE_FILL_MODE_NON_SOLID] != true
        return needsDivisorShim to needsFillModeEmulation
    }

    /** 检测完成或读取缓存记录后刷新门控状态 */
    fun refresh(needsDivisorShim: Boolean, needsFillModeEmulation: Boolean) {
        needsVulkanShim = needsDivisorShim || needsFillModeEmulation
    }
}
