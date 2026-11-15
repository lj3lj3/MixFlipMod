package com.parallelc.mixflipmod.hook

import com.parallelc.mixflipmod.Prefs
import com.parallelc.mixflipmod.hook.util.after
import com.parallelc.mixflipmod.hook.util.findClass
import com.parallelc.mixflipmod.hook.util.getField
import com.parallelc.mixflipmod.hook.util.hook
import com.parallelc.mixflipmod.hook.util.logInfo
import com.parallelc.mixflipmod.hook.util.method
import com.parallelc.mixflipmod.hook.util.prefEnabled
import com.parallelc.mixflipmod.hook.util.safeHook
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

object FlipHomeSelectableAppsHook {

    const val PACKAGE = "com.miui.fliphome"

    fun hook(param: PackageReadyParam) {
        if (!prefEnabled(Prefs.SYSTEM_COMPAT_CONFIG)) return
        safeHook("[$PACKAGE] selectable apps") {
            val cls = param.classLoader.findClass("com.miui.fliphome.model.DefaultLauncherApps")
            hook(cls.method("loadAllInstallApps"), after { chain, result ->
                restoreAllApps(chain.thisObject)
                result
            })
            hook(cls.method("checkContinuityApps")) { chain ->
                val result = chain.proceed()
                restoreAllApps(chain.thisObject)
                result
            }
            logInfo("[$PACKAGE] selectable apps hook installed")
        }
    }

    private fun restoreAllApps(instance: Any?) {
        instance ?: return
        val allApps = instance.getField("mAllApps") as? MutableMap<Any?, Any?> ?: return
        val installedApps = instance.getField("mAllInstallApps") as? Map<*, *> ?: return
        allApps.putAll(installedApps)
    }
}
