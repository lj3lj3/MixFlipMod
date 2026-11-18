package com.parallelc.mixflipmod.hook

import android.content.ComponentName
import android.graphics.Color
import android.graphics.Rect
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.view.DisplayCutout
import android.view.WindowInsets
import com.parallelc.mixflipmod.Prefs
import com.parallelc.mixflipmod.Prefs.FlipScreenMode
import com.parallelc.mixflipmod.hook.util.after
import com.parallelc.mixflipmod.hook.util.callMethod
import com.parallelc.mixflipmod.hook.util.createDexKitBridge
import com.parallelc.mixflipmod.hook.util.logInfo
import com.parallelc.mixflipmod.hook.util.findClass
import com.parallelc.mixflipmod.hook.util.getField
import com.parallelc.mixflipmod.hook.util.hook
import com.parallelc.mixflipmod.hook.util.log
import com.parallelc.mixflipmod.hook.util.method
import com.parallelc.mixflipmod.hook.util.runWithCleanup
import com.parallelc.mixflipmod.hook.util.safeHook
import com.parallelc.mixflipmod.hook.util.setField
import com.parallelc.mixflipmod.module
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

object SystemHook {

    fun hook(param: SystemServerStartingParam) {
        safeHook("[android] prefs cache") { initSystemPrefsCache() }
        safeHook("[android] ${Prefs.SYSTEM_COMPAT_CONFIG}") { hookCompatConfig(param) }
        safeHook("[android] ${Prefs.SYSTEM_FLIP_CONTINUITY}") { hookFlipContinuity(param) }
        safeHook("[android] ${Prefs.SYSTEM_FLIP_IME_PKG}") { hookFlipInputMethod(param) }
        safeHook("[android] ${Prefs.SYSTEM_FLIP_SCREEN_MODE}") { hookFlipScreenMode(param) }
        safeHook("[android] ${Prefs.SYSTEM_FLIP_HIDE_STATUS_BAR}") { hookHideStatusBarOnFlip(param) }
        safeHook("[android] ${Prefs.SYSTEM_FLIP_LETTERBOX_BLACK}") { hookFlipLetterboxColor(param) }
        safeHook("[android] ${Prefs.SYSTEM_FLIP_IME_INSETS_FIX}") { hookFlipImeInsetsFix(param) }
        safeHook("[android] ${Prefs.SYSTEM_GBOARD_CUTOUT_FIX}") {
            GboardWindowHook.hook(param) { systemGboardCutoutFixEnabled }
        }
    }

    // ── Prefs cache ──────────────────────────────────────────────

    private fun initSystemPrefsCache() {
        val prefs = module!!.getRemotePreferences(Prefs.NAME)
        refreshSystemPrefsCache(prefs)
        if (systemPrefsListener == null) {
            systemPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                if (key != null) updateSystemPrefsCache(sharedPreferences, key)
            }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
        }
    }

    private fun refreshSystemPrefsCache(prefs: SharedPreferences) {
        systemCompatConfigEnabled = prefs.getBoolean(Prefs.SYSTEM_COMPAT_CONFIG, false)
        systemFlipContinuityEnabled = prefs.getBoolean(Prefs.SYSTEM_FLIP_CONTINUITY, false)
        flipLetterboxBlackEnabled = prefs.getBoolean(Prefs.SYSTEM_FLIP_LETTERBOX_BLACK, false)
        hideStatusBarEnabled = prefs.getBoolean(Prefs.SYSTEM_FLIP_HIDE_STATUS_BAR, false)
        systemFlipImeInsetsFixEnabled = prefs.getBoolean(Prefs.SYSTEM_FLIP_IME_INSETS_FIX, false)
        systemGboardCutoutFixEnabled = prefs.getBoolean(Prefs.SYSTEM_GBOARD_CUTOUT_FIX, false)
        flipInputMethodPackageCache = normalizeFlipInputMethodPackage(
            prefs.getString(Prefs.SYSTEM_FLIP_IME_PKG, Prefs.DEFAULT_FLIP_IME_PKG)
        )
        flipScreenModeCache = prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(Prefs.FLIP_SCREEN_MODE_PREFIX)) return@mapNotNull null
            val modeInt = value as? Int ?: return@mapNotNull null
            val mode = FlipScreenMode.fromPref(modeInt) ?: return@mapNotNull null
            if (mode == FlipScreenMode.DEFAULT) return@mapNotNull null
            Prefs.flipScreenModePackage(key) to mode
        }.toMap()
        flipScreenScaleCache = prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(Prefs.FLIP_SCREEN_SCALE_PREFIX)) return@mapNotNull null
            val scale = (value as? Float) ?: return@mapNotNull null
            Prefs.flipScreenScalePackage(key) to scale
        }.toMap()
    }

    private fun updateSystemPrefsCache(prefs: SharedPreferences, key: String) {
        when {
            key == Prefs.SYSTEM_COMPAT_CONFIG -> {
                systemCompatConfigEnabled = prefs.getBoolean(key, false)
            }
            key == Prefs.SYSTEM_FLIP_CONTINUITY -> {
                systemFlipContinuityEnabled = prefs.getBoolean(key, false)
            }
            key == Prefs.SYSTEM_FLIP_LETTERBOX_BLACK -> {
                flipLetterboxBlackEnabled = prefs.getBoolean(key, false)
            }
            key == Prefs.SYSTEM_FLIP_HIDE_STATUS_BAR -> {
                hideStatusBarEnabled = prefs.getBoolean(key, false)
                applyHideStatusBarNow()
            }
            key == Prefs.SYSTEM_FLIP_IME_INSETS_FIX -> {
                systemFlipImeInsetsFixEnabled = prefs.getBoolean(key, false)
            }
            key == Prefs.SYSTEM_GBOARD_CUTOUT_FIX -> {
                systemGboardCutoutFixEnabled = prefs.getBoolean(key, false)
            }
            key == Prefs.SYSTEM_FLIP_IME_PKG -> {
                flipInputMethodPackageCache = normalizeFlipInputMethodPackage(
                    prefs.getString(key, Prefs.DEFAULT_FLIP_IME_PKG)
                )
                syncFlipInputMethodIfFolded()
            }
            key.startsWith(Prefs.FLIP_SCREEN_MODE_PREFIX) -> {
                val packageName = Prefs.flipScreenModePackage(key)
                val mode = FlipScreenMode.fromPref(prefs.getInt(key, FlipScreenMode.DEFAULT.prefValue))
                    ?: FlipScreenMode.DEFAULT
                val updated = flipScreenModeCache.toMutableMap()
                if (mode == FlipScreenMode.DEFAULT) {
                    updated.remove(packageName)
                } else {
                    updated[packageName] = mode
                }
                flipScreenModeCache = updated
            }
            key.startsWith(Prefs.FLIP_SCREEN_SCALE_PREFIX) -> {
                val packageName = Prefs.flipScreenScalePackage(key)
                val scale = prefs.getFloat(key, Prefs.DEFAULT_FLIP_SCREEN_SCALE)
                val updated = flipScreenScaleCache.toMutableMap()
                updated[packageName] = scale
                flipScreenScaleCache = updated
            }
        }
    }

    // ── Compat config ────────────────────────────────────────────

    private fun hookCompatConfig(param: SystemServerStartingParam) {
        val props = arrayOf("miui.continuity.policy", "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN")
        val mgr = param.classLoader.findClass("com.android.server.wm.ApplicationCompatManager")
        val propertyIntHook = Hooker { chain ->
            if (!systemCompatConfigEnabled) return@Hooker chain.proceed()
            when (chain.args[0]) {
                "miui.continuity.policy" -> 5
                "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN" -> 1
                else -> chain.proceed()
            }
        }
        hook(mgr.method("getPropertyIntByApplication", String::class.java, String::class.java), propertyIntHook)
        hook(mgr.method("getPropertyIntByActivity", String::class.java, ComponentName::class.java), propertyIntHook)

        val hasPropertyHook = Hooker { chain ->
            if (!systemCompatConfigEnabled) return@Hooker chain.proceed()
            if (chain.args[0] in props) true else chain.proceed()
        }
        hook(mgr.method("hasPropertyByApplication", String::class.java, String::class.java), hasPropertyHook)
        hook(mgr.method("hasPropertyByActivity", String::class.java, ComponentName::class.java), hasPropertyHook)
    }

    // ── Flip continuity ──────────────────────────────────────────

    private fun hookFlipContinuity(param: SystemServerStartingParam) {
        val c = param.classLoader.findClass("com.android.server.wm.InterceptActivityController")
        hook(c.method("isFlipContinuityEnabledFromSetting", String::class.java, Int::class.java, String::class.java)) { chain ->
            if (systemFlipContinuityEnabled) true else chain.proceed()
        }
    }

    // ── Flip screen mode ─────────────────────────────────────────

    private fun hookFlipScreenMode(param: SystemServerStartingParam) {
        val ipm = param.classLoader.findClass("com.android.server.pm.IPackageManagerBase")
        hook(ipm.method("getActivityInfo", ComponentName::class.java, Long::class.java, Int::class.java), after { _, result ->
            (result as? ActivityInfo)?.applyFlipScreenMode()
            result
        })
        hook(ipm.method("getApplicationInfo", String::class.java, Long::class.java, Int::class.java), after { _, result ->
            (result as? ApplicationInfo)?.applyFlipScreenMode()
            result
        })

        val sizeCompatStub = param.classLoader.findClass("android.sizecompat.MiuiAppSizeCompatModeStub")
        val getSizeCompatStub = sizeCompatStub.method("get")
        isFlipFolded = {
            foldedByDisplayGeometry()
                ?: runCatching { getSizeCompatStub.invoke(null)?.callMethod("isFlipFolded") as? Boolean }.getOrNull()
                ?: false
        }

        val activityRecord = param.classLoader.findClass("com.android.server.wm.ActivityRecord")
        val atmsImpl = param.classLoader.findClass("com.android.server.wm.ActivityTaskManagerServiceImpl")
        val controller = param.classLoader.findClass("com.android.server.wm.BoundsCompatController")
        val windowLayout = param.classLoader.findClass("android.view.WindowLayoutStubImpl")

        hook(controller.method("canUseFixedAspectRatio", Configuration::class.java), after { chain, result ->
            if (!isFlipFolded()) return@after result
            val packageName = activityPackageName(chain.thisObject?.getField("mOwner"))
            when (packageName?.let { flipScreenModeFor(it) }) {
                FlipScreenMode.FULL_SCREEN -> false
                FlipScreenMode.NO_SCALE,
                FlipScreenMode.SCALE -> true
                else -> result
            }
        })
        hook(atmsImpl.method("getGlobalScale", activityRecord), after { chain, result ->
            if (!isFlipFolded()) return@after result
            val packageName = activityPackageName(chain.args[0]) ?: return@after result
            when (flipScreenModeFor(packageName)) {
                FlipScreenMode.NO_SCALE -> FLIP_UNSCALE
                FlipScreenMode.SCALE -> flipScreenScaleFor(packageName)
                else -> result
            }
        })

        val propertyClass = param.classLoader.findClass($$"android.content.pm.PackageManager$Property")
        hook(
            ipm.method(
                "getPropertyAsUser",
                String::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!,
            )
        ) { chain ->
            val propertyName = chain.args[0] as? String
            val packageName = chain.args[1] as? String
            if (propertyName == WATCH_OVERLAY_PROPERTY &&
                packageName != null &&
                flipScreenModeFor(packageName) == FlipScreenMode.FULL_SCREEN
            ) {
                val className = chain.args[2] as? String
                runCatching {
                    propertyClass
                        .getDeclaredConstructor(
                            String::class.java, Boolean::class.javaPrimitiveType!!, String::class.java, String::class.java
                        )
                        .also { it.isAccessible = true }
                        .newInstance(propertyName, false, packageName, className)
                }.onFailure { log("getPropertyAsUser hook failed", it) }
                    .getOrElse { chain.proceed() }
            } else {
                chain.proceed()
            }
        }

        hook(windowLayout.method("getLayoutInDisplayCutoutMode", WindowManager.LayoutParams::class.java)) { chain ->
            if (!isFlipFolded()) return@hook chain.proceed()
            val attrs = chain.args[0] as? WindowManager.LayoutParams
            val packageName = attrs?.packageName ?: return@hook chain.proceed()
            if (flipScreenModeFor(packageName) != FlipScreenMode.FULL_SCREEN) return@hook chain.proceed()
            LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        val displayCutout = param.classLoader.findClass("android.view.DisplayCutout")
        val windowInsetsType = param.classLoader.findClass($$"android.view.WindowInsets$Type")
        val noDisplayCutout = displayCutout.getDeclaredField("NO_CUTOUT").also { it.isAccessible = true }.get(null)
        val displayCutoutType = windowInsetsType.method("displayCutout").invoke(null) as? Int ?: 0

        // 从派发给应用的 InsetsState 里裁剪两类源，两者门控互相独立：
        // displayCutout ← 外屏渲染模式 FULL_SCREEN（应用自己要求忽略摄像头区域）
        // statusBars   ← 外屏隐藏状态栏开关（隐藏后应用需要把顶部空间铺满）
        // 只改状态里的源，服务端原始状态不动，ControlTarget 动画与下拉 peek 不受影响
        val windowState = param.classLoader.findClass("com.android.server.wm.WindowState")
        val insetsStateClass = param.classLoader.findClass("android.view.InsetsState")
        hook(windowState.method("fillInsetsState", insetsStateClass, Boolean::class.javaPrimitiveType!!), after { chain, _ ->
            val window = chain.thisObject ?: return@after null
            val packageName = window.callMethod("getOwningPackage") as? String ?: return@after null
            val stripCutout = isFlipFolded() && flipScreenModeFor(packageName) == FlipScreenMode.FULL_SCREEN
            val stripStatusBar = shouldHideStatusBar(window)
            if (!stripCutout && !stripStatusBar) return@after null
            val state = chain.args[0]
            if (stripCutout) noDisplayCutout?.let { state.callMethod("setDisplayCutout", it) }
            for (i in (state.callMethod("sourceSize") as? Int ?: 0) - 1 downTo 0) {
                val source = state.callMethod("sourceAt", i) ?: continue
                val type = source.callMethod("getType") as? Int ?: continue
                if ((stripCutout && type == displayCutoutType) || (stripStatusBar && type == statusBarTypeBit)) {
                    state.callMethod("removeSourceAt", i)
                }
            }
        })

        // 派发给应用的客户端 InsetsState 来自 getCompatInsetsState()（与 fillInsetsState 的内部状态是两份），
        // 只裁剪 fillInsetsState 不会影响应用实际拿到的状态，这里对返回值做同样裁剪（改副本，避免污染共享状态）
        val insetsStateCopyCtor = insetsStateClass.getConstructor(insetsStateClass, Boolean::class.javaPrimitiveType!!)
            .also { it.isAccessible = true }
        hook(windowState.method("getCompatInsetsState"), after { chain, result ->
            if (result == null) return@after result
            val window = chain.thisObject ?: return@after result
            val packageName = window.callMethod("getOwningPackage") as? String ?: return@after result
            val stripCutout = isFlipFolded() && flipScreenModeFor(packageName) == FlipScreenMode.FULL_SCREEN
            val stripStatusBar = shouldHideStatusBar(window)
            if (!stripCutout && !stripStatusBar) return@after result
            runCatching {
                val copy = insetsStateCopyCtor.newInstance(result, true)
                if (stripCutout) noDisplayCutout?.let { copy.callMethod("setDisplayCutout", it) }
                for (i in (copy.callMethod("sourceSize") as? Int ?: 0) - 1 downTo 0) {
                    val source = copy.callMethod("sourceAt", i) ?: continue
                    val type = source.callMethod("getType") as? Int ?: continue
                    if ((stripCutout && type == displayCutoutType) || (stripStatusBar && type == statusBarTypeBit)) {
                        copy.callMethod("removeSourceAt", i)
                    }
                }
                copy
            }.getOrElse {
                log("strip compat insets failed", it)
                result
            }
        })

        // Fix appBounds in LaunchActivityItem (cold start). Both fields are owned by the item.
        val launchActivityItemClass = param.classLoader.findClass("android.app.servertransaction.LaunchActivityItem")
        hook(launchActivityItemClass.constructors.first { it.parameterCount > 10 }, after { chain, _ ->
            if (!isFlipFolded()) return@after null
            val info = chain.thisObject?.getField("mInfo") as? ActivityInfo ?: return@after null
            val packageName = info.packageName ?: return@after null
            if (flipScreenModeFor(packageName) != FlipScreenMode.FULL_SCREEN) return@after null
            val overrideConfig = chain.thisObject?.getField("mOverrideConfig") ?: return@after null
            fixConfigurationAppBounds(overrideConfig)
            chain.thisObject?.getField("mCurConfig")?.let { fixConfigurationAppBounds(it) }
        })

        val activityWindowInfoClass = param.classLoader.findClass("android.window.ActivityWindowInfo")

        // Fix appBounds for per-activity config updates. args[0] is mMergedOverrideConfiguration —
        // a persistent system field, so restore original appBounds after proceed() to avoid pollution.
        hook(activityRecord.method("scheduleConfigurationChanged", Configuration::class.java, activityWindowInfoClass)) { chain ->
            if (!isFlipFolded()) return@hook chain.proceed()
            val packageName = activityPackageName(chain.thisObject) ?: return@hook chain.proceed()
            if (flipScreenModeFor(packageName) != FlipScreenMode.FULL_SCREEN) return@hook chain.proceed()
            val windowConfig = runCatching { chain.args[0].getField("windowConfiguration") }.getOrNull()
            val originalAppBounds = (windowConfig?.callMethod("getAppBounds") as? Rect)?.let { Rect(it) }
            val bounds = windowConfig?.callMethod("getBounds") as? Rect
            if (bounds != null && !bounds.isEmpty) windowConfig.callMethod("setAppBounds", bounds)
            runWithCleanup({ windowConfig?.callMethod("setAppBounds", originalAppBounds) }) {
                chain.proceed()
            }
        }

        // Fix appBounds in ConfigurationChangeItem (process-global config). mConfiguration is a copy.
        val windowProcessController = param.classLoader.findClass("com.android.server.wm.WindowProcessController")
        val iApplicationThread = param.classLoader.findClass("android.app.IApplicationThread")
        val clientTransactionItem = param.classLoader.findClass("android.app.servertransaction.ClientTransactionItem")
        val configurationChangeItemClass = param.classLoader.findClass("android.app.servertransaction.ConfigurationChangeItem")
        hook(windowProcessController.method("scheduleClientTransactionItem", iApplicationThread, clientTransactionItem)) { chain ->
            if (!isFlipFolded()) return@hook chain.proceed()
            val item = chain.args[1] ?: return@hook chain.proceed()
            if (!configurationChangeItemClass.isInstance(item)) return@hook chain.proceed()
            val pkgList = runCatching { chain.thisObject?.getField("mPkgList") as? List<*> }.getOrNull()
            val packages = pkgList?.filterIsInstance<String>() ?: return@hook chain.proceed()
            if (packages.none { flipScreenModeFor(it) == FlipScreenMode.FULL_SCREEN }) return@hook chain.proceed()
            item.getField("mConfiguration")?.let { fixConfigurationAppBounds(it) }
            chain.proceed()
        }
    }

    // 外屏隐藏状态栏（服务端 ControlTarget 接管方案，独立开关，不依赖外屏渲染模式）：
    // 应用进程侧的 InsetsController 会按它自己的 requested（statusBars 可见）把 leash 拉回屏幕，
    // 服务端无法约束客户端，因此把 statusBars 的 control target 换成 InsetsPolicy 自有的
    // 服务端 ControlTarget（其 InsetsController 由本模块驱动），复用 MIUI 预置的
    // hideSystemBars/showSystemBars 通道（hideSystemBars 自动屏蔽 transient 中的类型，不与下拉冲突）。
    // 隐藏动画：ControlTarget 的 controller.hide → leash 移出屏幕（动画）；
    // 布局收敛：getCompatInsetsState 把 statusBars 从派发给应用的状态里摘掉 → 应用 insets=0 并铺满顶部；
    // 下拉 peek：InsetsPolicy.showTransient → 服务端 controller.show 让 leash 回到屏幕（动画），
    //   触摸后 SystemUI AutoHideController → hideTransientBars → hideTransient →
    //   updateBarControlTarget → hideSystemBars 自动收回。
    private fun hookHideStatusBarOnFlip(param: SystemServerStartingParam) {
        val windowState = param.classLoader.findClass("com.android.server.wm.WindowState")
        val insetsPolicy = param.classLoader.findClass("com.android.server.wm.InsetsPolicy")

        // WindowState 侧字段归一：mRequestedVisibleTypes 全部写入点只有 3 个（构造器初始
        // defaultVisible、单参与双参 setRequestedVisibleTypes），归一保证 topAppHidesSystemBar、
        // getInsetsStateWithVisibilityOverride（visible insets 计算）等服务端读取为隐藏语义；
        // proceed 后回写字段，不依赖 libxposed 参数替换语义
        val setRequestedVisibleTypes =
            windowState.method("setRequestedVisibleTypes", Int::class.javaPrimitiveType!!)
        val setRequestedVisibleTypesWithMask = windowState.method(
            "setRequestedVisibleTypes",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
        module!!.deoptimize(setRequestedVisibleTypes)
        module!!.deoptimize(setRequestedVisibleTypesWithMask)
        val windowStateCtor = windowState.declaredConstructors.first { it.parameterCount > 5 }
        hook(windowStateCtor, after { chain, _ -> normalizeStatusBarRequest(chain.thisObject) })
        hook(setRequestedVisibleTypes, after { chain, result ->
            normalizeStatusBarRequest(chain.thisObject)
            result
        })
        hook(setRequestedVisibleTypesWithMask, after { chain, result ->
            normalizeStatusBarRequest(chain.thisObject)
            result
        })

        // statusBars 的 control target 改为服务端 ControlTarget，避免客户端 leash 自行回弹
        val statusControlTargetInner =
            insetsPolicy.method("getStatusControlTargetInner", windowState, Boolean::class.javaPrimitiveType!!)
        module!!.deoptimize(statusControlTargetInner)
        hook(statusControlTargetInner) { chain ->
            if (!shouldHideStatusBar(chain.args[0])) return@hook chain.proceed()
            chain.thisObject?.getField("mTransientControlTarget") ?: chain.proceed()
        }

        // 服务端接管期间的隐藏/peek 语义全部落在具体类的调用点上（ControlTarget 完全继承
        // InsetsControlTarget 的接口默认方法，对默认方法的 hook 实测不被分发路径命中：
        // mInitiallyVisible=true、source visible=true 均为实证），一律以
        // 「provider 的 controlTarget 是服务端 ControlTarget 实例」作为门控，
        // 该状态只会在 getStatusControlTargetInner 命中后出现，无需再回查焦点窗口。
        val insetsSourceProvider = param.classLoader.findClass("com.android.server.wm.InsetsSourceProvider")
        val insetsTargetClass = param.classLoader.findClass("com.android.server.wm.InsetsTarget")
        val imeTrackerToken = param.classLoader.findClass($$"android.view.inputmethod.ImeTracker$Token")
        val displayPolicy = param.classLoader.findClass("com.android.server.wm.DisplayPolicy")

        // statusBars source 对客户端强制不可见（应用 insets 归零、可见 insets 计算一致）
        val updateClientVisibility =
            insetsSourceProvider.method("updateClientVisibility", insetsTargetClass, imeTrackerToken)
        module!!.deoptimize(updateClientVisibility)
        hook(updateClientVisibility) { chain ->
            val provider = chain.thisObject ?: return@hook chain.proceed()
            val source = runCatching { provider.getField("mSource") }.getOrNull()
                ?: return@hook chain.proceed()
            val type = runCatching { source.callMethod("getType") }.getOrNull() as? Int
                ?: return@hook chain.proceed()
            if (type and statusBarTypeBit == 0) return@hook chain.proceed()
            val controlTarget = runCatching { provider.getField("mControlTarget") }.getOrNull()
            if (!isServerStatusBarControlTarget(controlTarget)) return@hook chain.proceed()
            val clientVisible = provider.getField("mClientVisible") as? Boolean
                ?: return@hook chain.proceed()
            if (clientVisible) {
                provider.callMethod("setClientVisible", false)
                true
            } else {
                false
            }
        }

        // 下拉 peek：ControlTarget 继承的 canShowTransient 默认 false 会让原生流程走
        // showInsets 空操作分支（完全静默），这里在 statusBars 控制权已归服务端时
        // 直接复刻原生 transient 分支（SHOW_TYPES_FOR_SWIPE 等价位）
        val requestTransientBars =
            displayPolicy.method("requestTransientBars", windowState, Boolean::class.javaPrimitiveType!!)
        module!!.deoptimize(requestTransientBars)
        hook(requestTransientBars) { chain ->
            val swipeTarget = chain.args[0] ?: return@hook chain.proceed()
            val policy = chain.thisObject ?: return@hook chain.proceed()
            val provider = runCatching { swipeTarget.callMethod("getControllableInsetProvider") }.getOrNull()
                ?: return@hook chain.proceed()
            val controlTarget = runCatching { provider.callMethod("getControlTarget") }.getOrNull()
            if (!isServerStatusBarControlTarget(controlTarget)) return@hook chain.proceed()
            val displayContent = policy.getField("mDisplayContent")
            val insetsPolicyInstance = displayContent?.callMethod("getInsetsPolicy")
            insetsPolicyInstance?.callMethod("showTransient", transientShowTypes, chain.args[1])
        }

        // control 移交/回收后驱动服务端 controller 收回 leash；
        // hideSystemBars 会屏蔽 transient 中的类型，下拉展示期间天然不动作。
        // 收回后必须补一次 insets 变更派发：transient/悬浮面板期间应用拿到的是可见态的
        // 顶部 inset，服务端回到隐藏后若不给应用补派发，应用会一直保留状态栏高度的空白栏
        hook(insetsPolicy.method("updateBarControlTarget", windowState), after { chain, _ ->
            // 缓存 policy 与焦点窗口，供开关切换时立即生效
            lastInsetsPolicy = chain.thisObject
            lastTopWindow = chain.args[0]
            hideStatusBarFocused = shouldHideStatusBar(chain.args[0])
            if (!hideStatusBarFocused) return@after null
            runCatching { chain.thisObject?.callMethod("hideSystemBars", statusBarTypeBit) }
                .onFailure { log("hide status bar failed", it) }
            null
        })

        // 下拉显示动画：statusBars 控制权恒定归服务端 ControlTarget，control 不会因 transient
        // 切换而重新下发，原生 setRequestedVisibleTypes 对服务端 controller 是空操作；
        // 按 MIUI 强制隐藏分支同款方式显式调用 controller.show 把 leash 动画拉回屏幕。
        // 必须在原方法体把 requested 置为可见之前调用：controlAnimationUnchecked 会在
        // 「requested 已等于目标状态」时跳过动画，实测 show 放在 after 时被跳过（无动画）。
        // show 自身会把 requested 置为可见，原方法体随后保持该状态，保证 AutoHide 的
        // hide 不会因「已隐藏」被跳过；自动收回链路不变
        val showTransient = insetsPolicy.method(
            "showTransient",
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        )
        module!!.deoptimize(showTransient)
        hook(showTransient) { chain ->
            if (!hideStatusBarFocused) return@hook chain.proceed()
            runCatching {
                val target = chain.thisObject?.getField("mTransientControlTarget")
                val controller = target?.getField("mInsetsController")
                controller?.callMethod("show", statusBarTypeBit)
            }.onFailure { log("full screen transient show failed", it) }
            chain.proceed()
        }

        hookTransientStubInterceptor(param)

        logInfo("[android] full screen immersive status bars hook installed")
    }

    // MIUI InputManagerServiceStubImpl 在折叠外屏拦截 WMS transient 请求（导致短滑无 peek），
    // 仅在 FULL_SCREEN 应用持焦时放行；getInstance 为接口静态方法，用它延迟定位闭源实现类。
    // 只 hook 一次：getInstance 每次手势都会调用，重复 hook 会堆叠拦截层并破坏手势管线
    private fun hookTransientStubInterceptor(param: SystemServerStartingParam) {
        val inputManagerServiceStub =
            param.classLoader.findClass("com.android.server.input.InputManagerServiceStub")
        hook(inputManagerServiceStub.method("getInstance"), after { _, result ->
            val impl = result ?: return@after result
            if (stubInterceptorHooked) return@after result
            val intercept = runCatching {
                impl.javaClass.method(
                    "shouldInterceptRequestTransientBars",
                    Float::class.javaPrimitiveType!!,
                )
            }.getOrNull() ?: return@after result
            stubInterceptorHooked = true
            hook(intercept) { inner ->
                if (hideStatusBarFocused) false else inner.proceed()
            }
            logInfo("[android] transient stub interceptor released")
            result
        })
    }

    private fun normalizeStatusBarRequest(window: Any?) {
        if (!shouldHideStatusBar(window)) return
        val current = window?.getField("mRequestedVisibleTypes") as? Int ?: return
        if (current and statusBarTypeBit != 0) {
            window.setField("mRequestedVisibleTypes", current and statusBarTypeBit.inv())
        }
    }

    // 顶部状态栏是否该隐藏：独立开关 + 折起 + 应用窗口（锁屏、桌面、输入法与系统窗口一律排除）
    private fun shouldHideStatusBar(window: Any?): Boolean {
        if (!hideStatusBarEnabled) return false
        if (!isFlipFolded()) return false
        val packageName = window?.callMethod("getOwningPackage") as? String ?: return false
        if (packageName in hideStatusBarExcludedPackages) return false
        val type = runCatching { window?.getField("mAttrs")?.getField("type") as? Int }.getOrNull()
            ?: return false
        // BASE_APPLICATION / APPLICATION / APPLICATION_STARTING / DRAWN_APPLICATION
        return type in APP_WINDOW_TYPE_RANGE
    }

    // 开关切换即时生效：直接驱动当前焦点应用对应的 InsetsPolicy
    private fun applyHideStatusBarNow() {
        val policy = lastInsetsPolicy ?: return
        val hide = shouldHideStatusBar(lastTopWindow)
        hideStatusBarFocused = hide
        runCatching { policy.callMethod(if (hide) "hideSystemBars" else "showSystemBars", statusBarTypeBit) }
            .onFailure { log("toggle hide status bar failed", it) }
    }


    // FULL_SCREEN 应用的 statusBars 控制权在服务端：常态是 InsetsPolicy$ControlTarget，
    // getStatusControlTargetInner 改成 mTransientControlTarget 后则落在 InsetsPolicy$TransientControlTarget，
    // 两者都算服务端目标，否则按 exact class 判定的 gate 在稳态下永不命中
    private fun isServerStatusBarControlTarget(controlTarget: Any?): Boolean {
        val className = controlTarget?.javaClass?.simpleName ?: return false
        if (className == "ControlTarget") return true
        return hideStatusBarFocused && className == "TransientControlTarget"
    }

    // 折叠判定的权威来源：折起时默认显示是外屏 1208x1392（高宽比 1.15），展开是内屏 1224x2912（2.38），
    // 按短边 1.6 倍阈值区分。MiuiAppSizeCompatModeStub#isFlipFolded 在开机未解锁时会一直返回 false，
    // 会让全部沉浸 hook 短路，这里以显示尺寸为主、stub 为兜底；一秒内复用缓存避免热路径反复调用
    private fun foldedByDisplayGeometry(): Boolean? {
        val now = SystemClock.uptimeMillis()
        // 一秒内复用结果；取不到显示信息时也计入缓存时间，避免热路径反复反射
        if (now - foldedGeometryCachedAt < 1000L) return if (foldedGeometryKnown) foldedGeometryCache else null
        foldedGeometryCachedAt = now
        val folded = runCatching {
            val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val global = globalClass.getDeclaredMethod("getInstance").also { it.isAccessible = true }.invoke(null)
                ?: return@runCatching null
            val info = globalClass
                .getDeclaredMethod("getDisplayInfo", Int::class.javaPrimitiveType!!)
                .also { it.isAccessible = true }
                .invoke(global, Display.DEFAULT_DISPLAY) ?: return@runCatching null
            val width = info.javaClass.getField("logicalWidth").getInt(info)
            val height = info.javaClass.getField("logicalHeight").getInt(info)
            if (width <= 0 || height <= 0) return@runCatching null
            minOf(width, height) * 1.6f > maxOf(width, height)
        }.getOrNull()
        foldedGeometryKnown = folded != null
        if (folded != null) foldedGeometryCache = folded
        return folded
    }

    private fun hookFlipLetterboxColor(param: SystemServerStartingParam) {
        val overrides = param.classLoader.findClass("com.android.server.wm.AppCompatLetterboxOverrides")
        hook(overrides.method("getLetterboxBackgroundColor")) { chain ->
            if (!flipLetterboxBlackEnabled || !isFlipFolded()) return@hook chain.proceed()
            val letterbox = chain.thisObject?.getField("mLetterbox") ?: return@hook chain.proceed()
            val smallLetterbox = runCatching { letterbox.callMethod("isFlipSmallLetterBox") as? Boolean }
                .getOrNull() == true
            if (!smallLetterbox) return@hook chain.proceed()
            Color.valueOf(Color.BLACK)
        }
        logInfo("[android] ${Prefs.SYSTEM_FLIP_LETTERBOX_BLACK} hook installed")
    }

    private fun ActivityInfo.applyFlipScreenMode() {
        val mode = flipScreenModeFor(packageName)
        if (mode == FlipScreenMode.DEFAULT) return
        metaData = (if (metaData != null) Bundle(metaData) else Bundle()).also {
            it.putInt(FLIP_SCREEN_META_DATA, mode.prefValue)
        }
    }

    private fun ApplicationInfo.applyFlipScreenMode() {
        val mode = flipScreenModeFor(packageName)
        if (mode == FlipScreenMode.DEFAULT) return
        metaData = (if (metaData != null) Bundle(metaData) else Bundle()).also {
            it.putInt(FLIP_SCREEN_META_DATA, mode.prefValue)
        }
    }

    private fun flipScreenModeFor(packageName: String): FlipScreenMode {
        return flipScreenModeCache[packageName] ?: FlipScreenMode.DEFAULT
    }

    private fun flipScreenScaleFor(packageName: String): Float {
        return flipScreenScaleCache[packageName] ?: Prefs.DEFAULT_FLIP_SCREEN_SCALE
    }

    // ── Flip input method ───────────────────────────────────────

    private fun hookFlipInputMethod(param: SystemServerStartingParam) {
        val switcher = param.classLoader.findClass("com.android.server.inputmethod.SogouInputMethodSwitcher")
        val immsClass = param.classLoader.findClass("com.android.server.inputmethod.InputMethodManagerService")
        inputMethodServiceImplClass = param.classLoader.findClass("com.android.server.inputmethod.InputMethodManagerServiceImpl")
        inputMethodSettingsRepositoryClass = param.classLoader.findClass("com.android.server.inputmethod.InputMethodSettingsRepository")

        val switcherIsSogou = switcher.method("isSogouMethodLocked", String::class.java)
        val serviceImplIsSogou = inputMethodServiceImplClass!!.method("isSogouMethodLocked", immsClass, String::class.java)

        createDexKitBridge(param.classLoader).use { bridge ->
            listOf(switcherIsSogou, serviceImplIsSogou).forEach { target ->
                bridge.findMethod {
                    matcher {
                        invokeMethods {
                            add {
                                declaredClass(target.declaringClass.name)
                                name = target.name
                            }
                        }
                    }
                }.forEach { runCatching { module!!.deoptimize(it.getMethodInstance(param.classLoader)) } }
            }
        }

        hook(switcherIsSogou) { chain ->
            val methodId = chain.args[0] as? String ?: return@hook chain.proceed()
            val userId = chain.thisObject?.getField("mService")?.getField("mCurrentImeUserId") as? Int
                ?: return@hook chain.proceed()
            inputMethodPackage(methodId, userId)?.let { it == flipInputMethodPackageCache }
                ?: chain.proceed()
        }

        hook(serviceImplIsSogou) { chain ->
            val service = chain.args[0] ?: return@hook chain.proceed()
            val methodId = chain.args[1] as? String ?: return@hook chain.proceed()
            val userId = service.getField("mCurrentImeUserId") as? Int ?: return@hook chain.proceed()
            inputMethodPackage(methodId, userId)?.let { it == flipInputMethodPackageCache }
                ?: chain.proceed()
        }
    }

    // Xiaomi consumes DisplayCutout in size-compat windows, but leaves its InsetsSource.
    // At ROTATION_180 the scaled left source overlaps the unscaled window origin.
    // Visible insets then contain a left inset absent from content insets, triggering
    // ViewRootImpl.scrollToRectOrFocus and panning an already IME-aware app a second time.
    private fun hookFlipImeInsetsFix(param: SystemServerStartingParam) {
        val windowState = param.classLoader.findClass("com.android.server.wm.WindowState")
        val insetsState = param.classLoader.findClass("android.view.InsetsState")
        val copyState = insetsState.getConstructor(insetsState, Boolean::class.javaPrimitiveType!!)
        val sizeCompatStub = param.classLoader.findClass("android.sizecompat.MiuiAppSizeCompatModeStub")
        val getSizeCompatStub = sizeCompatStub.method("get")
        var failureLogged = false

        // Invalidate compiled callers so getCompatInsetsState cannot be bypassed
        // by an inlined copy in the client inset dispatch path.
        module!!.deoptimize(windowState.method("fillInsetsState", insetsState, Boolean::class.javaPrimitiveType!!))

        // This feeds both initial relayout and later asynchronous inset updates. Work
        // on a copy: the original may be shared with other windows or the display.
        hook(windowState.method("getCompatInsetsState"), after { chain, result ->
            if (!systemFlipImeInsetsFixEnabled || result == null) return@after result
            runCatching {
                if (getSizeCompatStub.invoke(null)?.callMethod("isFlipFolded") != true) return@runCatching result
                val window = chain.thisObject ?: return@runCatching result
                if (window.callMethod("getDisplayId") != 0) return@runCatching result
                val activity = window.getField("mActivityRecord") ?: return@runCatching result
                if (activity.getField("mHyperStub")?.callMethod("inMiuiSizeCompatMode") != true) return@runCatching result
                val cutout = result.callMethod("getDisplayCutout") as? DisplayCutout ?: return@runCatching result
                if (cutout.callMethod("isEmpty") != true) return@runCatching result
                val cutoutType = WindowInsets.Type.displayCutout()
                val sourceCount = result.callMethod("sourceSize") as Int
                val indices = (sourceCount - 1 downTo 0).filter { index ->
                    result.callMethod("sourceAt", index)?.callMethod("getType") == cutoutType
                }
                if (indices.isEmpty()) return@runCatching result
                copyState.newInstance(result, true).also { copy ->
                    indices.forEach { copy.callMethod("removeSourceAt", it) }
                }
            }.getOrElse {
                if (!failureLogged) {
                    failureLogged = true
                    log("flip IME insets fix failed", it)
                }
                result
            }
        })
    }

    private fun normalizeFlipInputMethodPackage(packageName: String?): String {
        return packageName
            ?.trim()
            ?.ifEmpty { Prefs.DEFAULT_FLIP_IME_PKG }
            ?: Prefs.DEFAULT_FLIP_IME_PKG
    }

    private fun inputMethodPackage(methodId: String, userId: Int): String? {
        val repository = inputMethodSettingsRepositoryClass ?: return null
        val settings = repository.method("get", Int::class.java).invoke(null, userId) ?: return null
        val imi = settings.callMethod("getMethodMap")?.callMethod("get", methodId) ?: return null
        return imi.callMethod("getPackageName") as? String
    }

    private fun syncFlipInputMethodIfFolded() {
        runCatching {
            val serviceImplClass = inputMethodServiceImplClass ?: return
            val serviceImpl = serviceImplClass.method("getInstance").invoke(null) ?: return
            if (serviceImpl.getField("mDeviceFolded") as? Boolean != true) return

            val context = serviceImpl.getField("mContext") as? Context ?: return
            val imms = serviceImpl.getField("mImms") ?: return
            val userId = imms.getField("mCurrentImeUserId") as? Int ?: return
            val methodId = inputMethodIdForPackage(flipInputMethodPackageCache, userId) ?: return

            val resolver = context.contentResolver
            val secureSettingsClass = Settings.Secure::class.java
            val getStringForUser = secureSettingsClass.method(
                "getStringForUser",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!
            )
            val putStringForUser = secureSettingsClass.method(
                "putStringForUser",
                android.content.ContentResolver::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!
            )
            val current = getStringForUser.invoke(null, resolver, DEFAULT_INPUT_METHOD, userId) as? String
            if (current != methodId) {
                putStringForUser.invoke(null, resolver, DEFAULT_INPUT_METHOD, methodId, userId)
            }
        }.onFailure { log("syncFlipInputMethodIfFolded failed", it) }
    }

    private fun inputMethodIdForPackage(packageName: String, userId: Int): String? {
        val repository = inputMethodSettingsRepositoryClass ?: return null
        val settings = repository.method("get", Int::class.java).invoke(null, userId) ?: return null
        val methodMap = settings.callMethod("getMethodMap") ?: return null
        val backingMap = methodMap.getField("mMap") as? Map<*, *> ?: return null
        return backingMap.entries.firstNotNullOfOrNull { (methodId, imi) ->
            val id = methodId as? String ?: return@firstNotNullOfOrNull null
            val imePackageName = imi?.callMethod("getPackageName") as? String
            if (imePackageName == packageName) id else null
        }
    }

    private fun fixConfigurationAppBounds(configuration: Any?) {
        val config = configuration ?: return
        runCatching {
            val windowConfiguration = config.getField("windowConfiguration") ?: return@runCatching
            val bounds = windowConfiguration.callMethod("getBounds") as? Rect
            if (bounds != null && !bounds.isEmpty) {
                windowConfiguration.callMethod("setAppBounds", bounds)
            }
        }
    }

    // ── Utility ──────────────────────────────────────────────────

    private fun activityPackageName(activityRecord: Any?): String? {
        return runCatching {
            activityRecord?.getField("packageName") as? String
        }.getOrNull()
    }

    // ── Constants & state ────────────────────────────────────────

    private const val FLIP_SCREEN_META_DATA = "miui.supportFlipFullScreen"
    private const val WATCH_OVERLAY_PROPERTY = "miui.supportFlipWatchOverlayGroupView"
    private const val DEFAULT_INPUT_METHOD = "default_input_method"
    private const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3
    private const val FLIP_UNSCALE = 1.0f

    // WindowInsets.Type.statusBars() 的位掩码，用于归一 mRequestedVisibleTypes
    private val statusBarTypeBit = WindowInsets.Type.statusBars()

    // DisplayPolicy.SHOW_TYPES_FOR_SWIPE 的等价位（statusBars|navigationBars），
    // 下拉 peek 复刻原生 transient 分支时传给 InsetsPolicy.showTransient
    private val transientShowTypes = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()

    // 外屏隐藏状态栏开关（改动后需重载系统框架生效）
    @Volatile
    private var hideStatusBarEnabled = false

    // 最近一次 updateBarControlTarget 的焦点窗口是否处于「隐藏状态栏」语义，
    // 供 InputManagerServiceStub 的 transient 拦截放行与 peek 判断使用
    @Volatile
    private var hideStatusBarFocused = false

    // 最近一次 updateBarControlTarget 的 policy 与焦点窗口，开关切换时用它即时生效
    @Volatile
    private var lastInsetsPolicy: Any? = null
    @Volatile
    private var lastTopWindow: Any? = null

    // 隐藏状态栏不生效的包：锁屏/通知栏、外屏桌面、主屏桌面
    private val hideStatusBarExcludedPackages =
        setOf("com.android.systemui", "com.miui.fliphome", "com.miui.home")

    // WindowManager.LayoutParams.TYPE_BASE_APPLICATION..TYPE_DRAWN_APPLICATION，即应用窗口家族
    private val APP_WINDOW_TYPE_RANGE = 1..4

    // transient 拦截器是否已完成一次性 hook，防止 getInstance 重复调用导致 hook 堆叠
    @Volatile
    private var stubInterceptorHooked = false

    // 折叠判定缓存（见 foldedByDisplayGeometry），一秒内复用，避免热路径反复查显示信息
    @Volatile
    private var foldedGeometryCache = false
    @Volatile
    private var foldedGeometryKnown = false
    @Volatile
    private var foldedGeometryCachedAt = 0L

    @Volatile
    private var systemCompatConfigEnabled = false
    @Volatile
    private var systemFlipContinuityEnabled = false
    @Volatile
    private var flipLetterboxBlackEnabled = false
    @Volatile
    private var systemFlipImeInsetsFixEnabled = false
    @Volatile
    private var systemGboardCutoutFixEnabled = false
    @Volatile
    private var flipInputMethodPackageCache = Prefs.DEFAULT_FLIP_IME_PKG
    @Volatile
    private var flipScreenModeCache: Map<String, FlipScreenMode> = emptyMap()
    @Volatile
    private var flipScreenScaleCache: Map<String, Float> = emptyMap()
    @Volatile
    private var inputMethodServiceImplClass: Class<*>? = null
    @Volatile
    private var inputMethodSettingsRepositoryClass: Class<*>? = null
    private var systemPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var isFlipFolded: () -> Boolean = { false }
}
