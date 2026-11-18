package com.parallelc.mixflipmod

import com.parallelc.mixflipmod.hook.FlipHomeHook
import com.parallelc.mixflipmod.hook.FlipHomeSelectableAppsHook
import com.parallelc.mixflipmod.hook.GboardHook
import com.parallelc.mixflipmod.hook.HideOuterHook
import com.parallelc.mixflipmod.hook.PersonalAssistantHook
import com.parallelc.mixflipmod.hook.SogouHook
import com.parallelc.mixflipmod.hook.SystemHook
import com.parallelc.mixflipmod.hook.SystemUIHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

class Main : XposedModule() {

    // 本进程内已完成 hook 安装的包，Vector 重复回调时直接跳过
    private val readyPackages = mutableSetOf<String>()

    // 过渡 classloader（dex 未填充）无法加载目标包的类，用它判断是否需要等下一次回调
    private fun ClassLoader.hasDexElements(): Boolean = runCatching {
        val pathList = javaClass.getDeclaredField("pathList").also { it.isAccessible = true }.get(this)
        val elements = pathList?.javaClass?.getDeclaredField("dexElements")
            ?.also { it.isAccessible = true }?.get(pathList) as? Array<*>
        elements == null || elements.isNotEmpty()
    }.getOrDefault(true)

    private val hooks = listOf(
        FlipHomeHook,
        SystemUIHook,
        HideOuterHook,
        SogouHook,
        PersonalAssistantHook,
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        SystemHook.hook(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        // Vector 对同一个包会回调多次，其中一次拿到的是 dex 尚未填充的过渡 classloader
        //（异常里 DexPathList 为空），用它加载任何目标类都会 ClassNotFoundException，
        // 直接跳过等下一次有效回调；同一包只安装一次，避免 hook 重复堆叠
        if (!param.classLoader.hasDexElements()) return
        if (!readyPackages.add(param.packageName)) return
        if (param.packageName == GboardHook.PACKAGE) GboardHook.hook(param)
        if (param.packageName == FlipHomeSelectableAppsHook.PACKAGE) FlipHomeSelectableAppsHook.hook(param)
        hooks.filter { param.packageName in it.targetPackages }.forEach { it.hook(param) }
    }
}
