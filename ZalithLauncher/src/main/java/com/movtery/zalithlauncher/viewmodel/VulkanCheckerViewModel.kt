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

package com.movtery.zalithlauncher.viewmodel

import androidx.annotation.Keep
import androidx.lifecycle.ViewModel
import com.movtery.zalithlauncher.game.plugin.driver.Driver
import com.movtery.zalithlauncher.game.plugin.driver.DriverPluginManager
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.utils.isLowerVer
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.launcherMMKV
import com.movtery.zalithlauncher.ui.vulkan_checker.VCOperation
import com.movtery.zalithlauncher.utils.GSON
import com.movtery.zalithlauncher.utils.device.VulkanCapabilities
import com.movtery.zalithlauncher.utils.device.VulkanChecker
import com.movtery.zalithlauncher.utils.device.VulkanRequirements
import com.movtery.zalithlauncher.utils.device.VulkanShim
import com.movtery.zalithlauncher.utils.device.normalizeMcVersion
import com.movtery.zalithlauncher.utils.device.profileSupport
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

private const val TAG = "VulkanCheckerViewModel"
private const val KEY_VULKAN_CHECK_RECORD = "vulkanCheckRecord"

@Keep
data class VulkanCheckRecord(
    val useTurnip: Boolean,
    val driverPath: String,
    val needsDivisorShim: Boolean = false,
    val needsFillModeEmulation: Boolean = false,
    /** 设备可支持的 Minecraft 版本范围，[Range.until] 为排他上界，null 表示无上界 */
    val supportedRanges: List<Range> = emptyList(),
    val version: Int = 0,
) {
    @Keep
    data class Range(
        val since: String,
        val until: String?
    )

    /** 判断指定 Minecraft 版本是否落在已支持的版本范围内 */
    fun isSupported(mcVersion: String): Boolean {
        return supportedRanges.any { range ->
            !mcVersion.isLowerVer(range.since) &&
                    (range.until == null || mcVersion.isLowerVer(range.until))
        }
    }
}

class VulkanCheckerViewModel: ViewModel() {
    private val _vcOperation = MutableStateFlow<VCOperation>(VCOperation.None)
    val vcOperation = _vcOperation.asStateFlow()

    private val mutex = Mutex()

    var vulkanCheckerCont: (Continuation<Unit>)? = null
        private set

    fun changeOperation(operation: VCOperation) {
        _vcOperation.update { operation }
    }

    suspend fun waitForVulkanChecker(version: Version) {
        suspendCancellableCoroutine { cont ->
            vulkanCheckerCont = cont
            changeOperation(VCOperation.Tip(version))

            cont.invokeOnCancellation {
                vulkanCheckerCont = null
            }
        }
    }

    fun resumeCont() {
        vulkanCheckerCont?.resume(Unit)
        vulkanCheckerCont = null
    }

    suspend fun check(version: Version): Pair<VulkanCapabilities?, Boolean> {
        return mutex.withLock {
            val driver = DriverPluginManager.getDriver(version.getDriver())
            val useTurnip = useTurnip(driver)
            val capabilities = doCheck(useTurnip, driver)

            //shim 仅包装系统 Vulkan 加载器：请求 Turnip 但加载失败回落系统加载器时，
            //检测与游戏运行时实际使用的都是系统驱动，shim 同样适用
            val (needsDivisorShim, needsFillModeEmulation) = VulkanShim.evaluate(capabilities)
            VulkanShim.refresh(needsDivisorShim, needsFillModeEmulation)

            //shim 生效后游戏可见的能力按补齐后的状态评估：
            //divisor 由 KHR 合成补报；fillModeNonSolid 由降级模拟保证管线可创建
            val effective = capabilities?.let {
                it.copy(
                    extensions = if (needsDivisorShim) {
                        it.extensions + VulkanShim.EXT_VERTEX_ATTRIBUTE_DIVISOR
                    } else it.extensions,
                    features = if (needsFillModeEmulation) {
                        it.features + (VulkanShim.FEATURE_FILL_MODE_NON_SOLID to true)
                    } else it.features
                )
            }
            saveRecord(
                VulkanCheckRecord(
                    useTurnip = useTurnip,
                    driverPath = driverPath(useTurnip, driver),
                    needsDivisorShim = needsDivisorShim,
                    needsFillModeEmulation = needsFillModeEmulation,
                    supportedRanges = effective?.profileSupport()
                        ?.filter { it.supported }
                        ?.map { VulkanCheckRecord.Range(since = it.since, until = it.until) }
                        ?: emptyList(),
                    version = VulkanRequirements.VULKAN_REQUIREMENTS_VERSION
                )
            )
            effective to useTurnip
        }
    }

    suspend fun ensureSupported(version: Version): Boolean {
        val driver = DriverPluginManager.getDriver(version.getDriver())
        val useTurnip = useTurnip(driver)
        val mcVersion = version.getVersionInfo()?.minecraftVersion?.let(::normalizeMcVersion)
        val path = driverPath(useTurnip, driver)

        loadRecord()?.takeIf { last ->
            //判断是否使用当前版本的检查器进行检测，版本不一致则数据失效重新检查
            last.version == VulkanRequirements.VULKAN_REQUIREMENTS_VERSION &&
            //判断Turnip环境是否不一致，不一致则数据失效重新检查
            last.useTurnip == useTurnip && last.driverPath == path
        }?.let {
            //同一驱动状态下设备能力不变，直接按已支持的版本范围判定
            VulkanShim.refresh(it.needsDivisorShim, it.needsFillModeEmulation)
            return mcVersion != null && it.isSupported(mcVersion)
        }

        //记录不存在或驱动状态不一致时走完整的检测流程，检测完成后结果已保存；
        //门控先回到安全侧，避免残留其他驱动状态下的判定
        VulkanShim.refresh(false, false)
        waitForVulkanChecker(version)
        val record = loadRecord() ?: return false
        VulkanShim.refresh(record.needsDivisorShim, record.needsFillModeEmulation)
        return mcVersion != null && record.isSupported(mcVersion)
    }

    /** 系统驱动开关开启时，检测与游戏运行时都应对准系统加载器，而非驱动插件 */
    private fun useTurnip(driver: Driver): Boolean =
        !driver.isLauncher && !AllSettings.zinkPreferSystemDriver.getValue()

    private fun driverPath(useTurnip: Boolean, driver: Driver): String {
        return if (useTurnip) driver.path else ""
    }

    private suspend fun doCheck(useTurnip: Boolean, driver: Driver): VulkanCapabilities? {
        return withContext(Dispatchers.IO) {
            if (useTurnip) {
                val tempDir = File(PathManager.DIR_CACHE, "vulkan_temp")
                VulkanChecker.checkCapabilities(null, driver.path, tempDir.absolutePath)
            } else {
                VulkanChecker.checkCapabilities(null, null, null)
            }
        }
    }

    private fun loadRecord(): VulkanCheckRecord? {
        val json = launcherMMKV().getString(KEY_VULKAN_CHECK_RECORD, null) ?: return null
        return runCatching {
            GSON.fromJson(json, VulkanCheckRecord::class.java)
        }.onFailure { e ->
            Logger.warning(TAG, "Failed to read vulkan check record", e)
        }.getOrNull()
    }

    private fun saveRecord(record: VulkanCheckRecord) {
        runCatching {
            launcherMMKV()
                .putString(KEY_VULKAN_CHECK_RECORD, GSON.toJson(record))
                .apply()
        }.onFailure { e ->
            Logger.warning(TAG, "Failed to save vulkan check record", e)
        }
    }
}