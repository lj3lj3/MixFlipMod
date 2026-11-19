package com.parallelc.mixflipmod.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.parallelc.mixflipmod.Prefs
import com.parallelc.mixflipmod.hook.util.after
import com.parallelc.mixflipmod.hook.util.callMethod
import com.parallelc.mixflipmod.hook.util.findClass
import com.parallelc.mixflipmod.hook.util.getField
import com.parallelc.mixflipmod.hook.util.hook
import com.parallelc.mixflipmod.hook.util.log
import com.parallelc.mixflipmod.hook.util.logInfo
import com.parallelc.mixflipmod.hook.util.method
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 外屏锁屏快捷按钮：在原生相机圆按钮右侧追加手电筒 / 静音两个按钮。
 *
 * 与相机实现保持一致：
 * - 触摸走面板管道（TouchHandler.onInterceptTouchEvent → onTouchEvent），子 View 分发拿不到手势；
 * - 拖动动画画在独立的 type=2017 半透明覆盖窗口里（与 MiuiTinyKeyguardCameraView 相同），
 *   永远盖在通知之上；
 * - 背景虚化用 SystemUI 的 BlurUtils.applyBlur 作用于覆盖窗口自己的 ViewRootImpl，松手渐隐；
 * - 过半叠加相机 startActiveAnim 同款弹簧动画与 mesh_heavy 触感；
 * - 手电筒触发走控制中心同源控制器（本机弹"翻转手机"引导），状态以 Settings.Global torch_state 为准。
 */
object TinyKeyguardShortcutHook : BaseHook() {
    override val targetPackages = listOf("com.android.systemui")

    private const val TAG_SHORTCUT_ROW = "mixflipmod_lock_shortcuts"

    // 整行宽度对齐通知列表（tiny_keyguard_notif_list_width = 248.85dp）：18 + 69×3 + 12×2 = 249dp
    private const val BUTTON_SIZE_DP = 69f
    private const val BUTTON_GAP_DP = 12f
    private const val ICON_SIZE_DP = 34f
    private const val ROW_LEFT_DP = 18f + BUTTON_SIZE_DP + BUTTON_GAP_DP
    private const val ROW_BOTTOM_DP = 18f

    // 与 MiuiTinyKeyguardCameraView 相同的覆盖窗口参数
    private const val OVERLAY_WINDOW_TYPE = 2017
    private const val OVERLAY_WINDOW_FLAGS = 218171160

    private var torchOn = false
    private var torchCameraId: String? = null
    private var flashlightController: Any? = null
    private var nativeCameraBgWidthId = 0
    private var nativeCameraBgSizePx = 0

    private var rowRef: WeakReference<ViewGroup>? = null
    private var cameraIconRef: WeakReference<View>? = null
    private var shortcuts: List<ShortcutSpec> = emptyList()
    private var blurUtils: Any? = null
    private var lastBlurRatio = 0f
    private var blurAnimator: ValueAnimator? = null
    private var activeAnimPer = 0f
    private var activeAnimator: ValueAnimator? = null

    private var activeButton: View? = null
    private var activeIcon: View? = null
    private var cameraDragActive = false
    private var armed = false
    private var downX = 0f
    private var downY = 0f
    private var maxPer = 0f

    private var overlay: FrameLayout? = null
    private var overlayContainer: FrameLayout? = null
    private var overlayWm: WindowManager? = null
    private var overlayStartCenterX = 0f
    private var overlayStartCenterY = 0f

    // 按钮定义：图标/动作/状态全部收口在这里，所有按钮共用同一套拖动与动画管道。
    // 后续接入模块设置后，改为按配置生成（开关、顺序、自定义应用）即可
    private class ShortcutSpec(
        val action: (Context) -> Unit,
        val isActive: (Context) -> Boolean,
        val iconOn: String,
        val iconOff: String,
    )

    private fun buildShortcuts(): List<ShortcutSpec> = listOf(
        ShortcutSpec(
            action = { ctx -> toggleTorch(ctx) },
            isActive = { isTorchOn() },
            iconOn = "ic_qs_flashlight_enabled",
            iconOff = "ic_qs_flashlight_disabled",
        ),
        ShortcutSpec(
            action = { ctx -> toggleMute(ctx) },
            isActive = { ctx -> isMuted(ctx) },
            iconOn = "ic_qs_mute_on",
            iconOff = "ic_qs_mute_off",
        ),
    )

    override fun setupHooks(prefKey: String, param: PackageReadyParam) {
        if (prefKey != Prefs.SYSTEMUI_TINY_LOCK_SHORTCUTS) return

        val cameraViewClass = param.classLoader.findClass("com.android.keyguard.widget.TinyCameraView")
        hookCameraSize(cameraViewClass)

        val ctor = cameraViewClass
            .getDeclaredConstructor(Context::class.java, AttributeSet::class.java)
            .also { it.isAccessible = true }
        hook(ctor) { chain ->
            val result = chain.proceed()
            runCatching { inject(chain.thisObject as? ViewGroup) }
                .onFailure { log("inject tiny lock shortcuts failed", it) }
            result
        }
        hookPanelTouch(param)
        hookBlurUtils(param)
        hookSelfTest(param)
        hookFlashlightController(param)
        hookRingerMode(param)
        hookNativeCameraOverlaySize(param)
        logInfo("[sysui] tiny lock shortcuts hook installed")
    }

    // 相机圆底 92.95dp / 图标 67.07dp 一起缩到 69dp / 34dp，和新增按钮统一
    private fun hookCameraSize(cameraViewClass: Class<*>) {
        hook(cameraViewClass.method("createCameraIconBgLayoutParams"), after { chain, result ->
            val density = (chain.thisObject as? View)?.resources?.displayMetrics?.density ?: 1f
            (result as? FrameLayout.LayoutParams)?.let { params ->
                val sizePx = (BUTTON_SIZE_DP * density).toInt()
                params.width = sizePx
                params.height = sizePx
            }
            result
        })
        hook(cameraViewClass.method("createCameraIconLayoutParams"), after { chain, result ->
            val density = (chain.thisObject as? View)?.resources?.displayMetrics?.density ?: 1f
            (result as? FrameLayout.LayoutParams)?.let { params ->
                val sizePx = (ICON_SIZE_DP * density).toInt()
                params.width = sizePx
                params.height = sizePx
            }
            result
        })
    }

    private fun inject(container: ViewGroup?) {
        val camera = container ?: return
        if (camera.findViewWithTag<View>(TAG_SHORTCUT_ROW) != null) return
        val context = camera.context ?: return
        val density = context.resources.displayMetrics.density

        val row = LinearLayout(context).apply {
            tag = TAG_SHORTCUT_ROW
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        shortcuts = buildShortcuts()
        shortcuts.forEach { addShortcut(row) }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = (ROW_LEFT_DP * density).toInt()
            bottomMargin = (ROW_BOTTOM_DP * density).toInt()
        }
        camera.addView(row, params)
        rowRef = WeakReference(row)
        cameraIconRef = WeakReference(
            runCatching { camera.callMethod("getCameraIconView") as? View }.getOrNull()
        )
        if (nativeCameraBgWidthId == 0) {
            nativeCameraBgWidthId = context.resources.getIdentifier(
                "tiny_keyguard_camera_bg_width",
                "dimen",
                "com.android.systemui",
            )
            nativeCameraBgSizePx = (BUTTON_SIZE_DP * density).toInt()
        }
        refreshButtonStates()
        registerTorchStateObserver(context)
        logInfo("[sysui] tiny lock shortcuts injected: ${row.childCount} buttons")
    }

    private fun addShortcut(row: LinearLayout) {
        val context = row.context
        val density = context.resources.displayMetrics.density
        val size = (BUTTON_SIZE_DP * density).toInt()
        val gap = (BUTTON_GAP_DP * density).toInt()
        val iconSize = (ICON_SIZE_DP * density).toInt()

        val button = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                if (row.childCount > 0) marginStart = gap
            }
        }
        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
        }
        button.addView(icon)
        row.addView(button)
    }

    // 图标、触发逻辑、状态都与控制中心磁贴一致：
    // 手电筒 = MiuiFlashlightControllerImpl（setFlashlight / isEnabled），
    // 静音 = AudioManagerHelper.toggleSilent(ctx, 4)，激活态 = 铃声模式 静音/振动
    private fun refreshButtonStates() {
        val row = rowRef?.get() ?: return
        val context = row.context
        shortcuts.forEachIndexed { index, spec ->
            applyButtonState(row.getChildAt(index), spec.isActive(context), spec.iconOn, spec.iconOff)
        }
    }

    private fun applyButtonState(button: View?, active: Boolean, onName: String, offName: String) {
        val container = button as? FrameLayout ?: return
        container.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            // 开启 = 不透明白；关闭 = 半透白
            setColor(if (active) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt())
        }
        val icon = container.getChildAt(0) as? ImageView ?: return
        val drawable = sysuiDrawable(container.context, if (active) onName else offName) ?: return
        icon.setImageDrawable(drawable)
        icon.setColorFilter(0xFF000000.toInt())
    }

    private fun sysuiDrawable(context: Context, resName: String): Drawable? = runCatching {
        val id = context.resources.getIdentifier(resName, "drawable", "com.android.systemui")
        if (id == 0) null else context.getDrawable(id)
    }.getOrNull()

    // 控制中心手电筒磁贴使用的同一个控制器实例，动作/状态全走它
    private fun hookFlashlightController(param: PackageReadyParam) {
        runCatching {
            val controllerClass = param.classLoader
                .findClass("com.android.systemui.controlcenter.policy.MiuiFlashlightControllerImpl")
            controllerClass.declaredConstructors.firstOrNull()?.let { ctor ->
                ctor.isAccessible = true
                hook(ctor) { chain ->
                    val result = chain.proceed()
                    flashlightController = chain.thisObject
                    refreshButtonStates()
                    result
                }
            }
            controllerClass.method("setFlashlight", Boolean::class.javaPrimitiveType!!).let { method ->
                hook(method, after { _, _ ->
                    Handler(Looper.getMainLooper()).post { refreshButtonStates() }
                })
            }
        }.onFailure { log("hook flashlight controller failed", it) }
    }

    // 静音状态变化（无论从哪改）都刷新按钮外观
    private fun hookRingerMode(param: PackageReadyParam) {
        runCatching {
            val audioManagerClass = param.classLoader.findClass("android.media.AudioManager")
            listOf("setRingerMode", "setRingerModeInternal").forEach { name ->
                runCatching {
                    val method = audioManagerClass.method(name, Int::class.javaPrimitiveType!!)
                    hook(method, after { _, _ ->
                        Handler(Looper.getMainLooper()).post { refreshButtonStates() }
                    })
                }
            }
        }.onFailure { log("hook ringer mode failed", it) }
    }

    // 原生相机拖动动画也按我们的尺寸取图标，避免按下瞬间从 92.95dp 跳到 69dp；
    // 同时把原生覆盖窗口里的相机 glyph（mIconView，64dp 矢量 + scaleType=CENTER）
    // 缩到 34dp 并按 mIconCenterX/Y 对中（原生按 69dp 摆位，会产生偏差）
    private fun hookNativeCameraOverlaySize(param: PackageReadyParam) {
        runCatching {
            val resourcesClass = param.classLoader.findClass("android.content.res.Resources")
            val method = resourcesClass.method("getDimensionPixelOffset", Int::class.javaPrimitiveType!!)
            hook(method) { chain ->
                val id = chain.args.getOrNull(0) as? Int ?: return@hook chain.proceed()
                if (id != 0 && id == nativeCameraBgWidthId) nativeCameraBgSizePx else chain.proceed()
            }
            val internalClass = param.classLoader.loadClass(
                "com.android.keyguard.MiuiTinyKeyguardCameraView\$MiuiKeyguardCameraViewInternal"
            )
            hook(internalClass.method("updateSizeForScreenSizeChange"), after { chain, _ ->
                shrinkCameraGlyph(chain.thisObject as? View)
            })
            hook(internalClass.method("updateViews"), after { chain, _ ->
                shrinkCameraGlyph(chain.thisObject as? View)
            })
        }.onFailure { log("hook camera overlay size failed", it) }
    }

    private fun shrinkCameraGlyph(internal: View?) {
        runCatching {
            val iconView = internal?.getField("mIconView") as? ImageView ?: return@runCatching
            val density = internal.resources.displayMetrics.density
            val size = (ICON_SIZE_DP * density).toInt()
            iconView.scaleType = ImageView.ScaleType.FIT_CENTER
            iconView.layoutParams = FrameLayout.LayoutParams(size, size)
            val centerX = (internal.getField("mIconCenterX") as? Float)
                ?: (internal.getField("mIconInitCenterX") as? Float)
                ?: return@runCatching
            val centerY = (internal.getField("mIconCenterY") as? Float)
                ?: (internal.getField("mIconInitCenterY") as? Float)
                ?: return@runCatching
            iconView.translationX = centerX - size / 2f
            iconView.translationY = centerY - size / 2f
        }
    }

    // 复用 SystemUI 现有的 BlurUtils（相机那套 window blur 就是它）
    private fun hookBlurUtils(param: PackageReadyParam) {
        runCatching {
            val delegateClass = param.classLoader.findClass("com.miui.systemui.statusbar.BlurUtilsDelegate")
            val ctor = delegateClass.declaredConstructors.firstOrNull() ?: return@runCatching
            ctor.isAccessible = true
            hook(ctor) { chain ->
                val result = chain.proceed()
                chain.args.firstOrNull {
                    it != null && it.javaClass.simpleName.startsWith("BlurUtils")
                }?.let { blurUtils = it }
                result
            }
        }.onFailure { log("hook blur utils failed", it) }
    }

    private fun applyWindowBlur(view: View, ratio: Float) {
        val utils = blurUtils ?: return
        runCatching {
            val clamped = ratio.coerceIn(0f, 1f)
            val radius = utils.javaClass
                .getMethod("blurRadiusOfRatio", Float::class.javaPrimitiveType!!)
                .invoke(utils, clamped) as Float
            val rootImpl = View::class.java
                .getDeclaredMethod("getViewRootImpl")
                .apply { isAccessible = true }
                .invoke(view) ?: return@runCatching
            utils.javaClass.getMethod(
                "applyBlur",
                Class.forName("android.view.ViewRootImpl", false, view.context.classLoader),
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            ).invoke(utils, rootImpl, radius.toInt(), false)
        }.onFailure { log("apply window blur failed", it) }
    }

    // 临时自测：只按下相机（不移动），观察按钮是否淡出
    private fun hookSelfTest(param: PackageReadyParam) {
        val panelClass = param.classLoader
            .findClass("com.android.keyguard.tinyPanel.TinyKeyguardPanelViewControllerImpl")
        val ctor = panelClass.declaredConstructors.firstOrNull() ?: return
        ctor.isAccessible = true
        hook(ctor) { chain ->
            val result = chain.proceed()
            val controller = chain.thisObject ?: return@hook result
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { runSelfTest(controller) }.onFailure { log("self test failed", it) }
            }, 5000L)
            result
        }
    }

    private fun runSelfTest(controller: Any) {
        val handler = controller.javaClass
            .getDeclaredField("mTouchHandler")
            .apply { isAccessible = true }
            .get(controller) ?: return
        val intercept = handler.javaClass.getMethod("onInterceptTouchEvent", MotionEvent::class.java)
        val onTouch = handler.javaClass.getMethod("onTouchEvent", MotionEvent::class.java)
        val x0 = 171f
        val y0 = 1221f
        val now = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x0, y0, 0)
        logInfo("[sysui] selftest camera down only intercept=" + intercept.invoke(handler, down))
        onTouch.invoke(handler, down)
        Handler(Looper.getMainLooper()).postDelayed({
            val up = MotionEvent.obtain(now, now + 3000L, MotionEvent.ACTION_UP, x0, y0, 0)
            onTouch.invoke(handler, up)
            up.recycle()
            down.recycle()
        }, 3000L)
    }

    // 面板：按下落在按钮行内就拦截，后续事件交给我们（与相机同一套管道）
    private fun hookPanelTouch(param: PackageReadyParam) {
        val panelClass = param.classLoader
            .findClass("com.android.keyguard.tinyPanel.TinyKeyguardPanelViewControllerImpl")
        val innerNames = listOf("TouchHandler") + (1..12).map { it.toString() }
        val candidates = panelClass.declaredClasses.toList() + innerNames.mapNotNull { name ->
            runCatching { param.classLoader.loadClass("${panelClass.name}\$$name") }.getOrNull()
        }
        val owner = candidates.firstOrNull { clazz ->
            clazz.declaredMethods.any { it.name == "onInterceptTouchEvent" }
        } ?: return

        hook(owner.method("onInterceptTouchEvent", MotionEvent::class.java)) { chain ->
            val event = chain.args[0] as? MotionEvent ?: return@hook chain.proceed()
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return@hook chain.proceed()
            val button = findShortcutAt(event)
            if (button != null) {
                startDrag(button, event)
                return@hook true
            }
            // 未命中我们的按钮：若命中相机圆，给我们的按钮一个淡出动画
            if (isOnCameraCircle(event)) {
                cameraDragActive = true
                fadeRowOnly(false)
            }
            chain.proceed()
        }

        hook(owner.method("onTouchEvent", MotionEvent::class.java)) { chain ->
            val event = chain.args[0] as? MotionEvent ?: return@hook chain.proceed()
            val button = activeButton
            if (button == null) {
                // 相机手势结束：淡入恢复我们的按钮
                if (cameraDragActive &&
                    (event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL)
                ) {
                    cameraDragActive = false
                    fadeRowOnly(true)
                }
                return@hook chain.proceed()
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> moveDrag(button, event)
                MotionEvent.ACTION_UP -> endDrag(button, event)
                MotionEvent.ACTION_CANCEL -> cancelDrag(button)
                else -> true
            }
        }
    }

    // ---------- 拖动流程 ----------

    private fun startDrag(button: View, event: MotionEvent) {
        activeButton = button
        activeIcon = (button as? ViewGroup)?.getChildAt(0)
        armed = false
        downX = event.rawX
        downY = event.rawY
        maxPer = 0f
        setSiblingsVisible(button, false)
        button.animate().alpha(0f).setDuration(120L).start()
        showDragOverlay(button)
        logInfo("[sysui] shortcut touch down")
    }

    private fun moveDrag(button: View, event: MotionEvent): Boolean {
        val root = button.rootView
        val screenWidth = root.width.toFloat().coerceAtLeast(1f)
        val dx = event.rawX - downX
        val up = (downY - event.rawY).coerceAtLeast(0f)
        val moveDistance = maxOf(abs(dx), up)
        val per = moveDistance / (screenWidth / 3f)
        maxPer = maxOf(maxPer, per)

        val container = overlayContainer ?: return true
        // 过半时叠加弹簧激活动画（相机 startActiveAnim 同款，权重 0.4）
        val scale = 1f + 0.5f * ((per + activeAnimPer * 0.4f) / 0.6f)
        container.scaleX = scale
        container.scaleY = scale
        container.alpha = (1f - 0.5f * per).coerceAtLeast(0.4f)
        val centerX = overlayStartCenterX + dx
        val centerY = overlayStartCenterY - up
        updateOverlayIconPosition(centerX, centerY)
        // 与相机一致：位移/270 施加窗口模糊
        lastBlurRatio = (moveDistance / 270f).coerceIn(0f, 1f)
        applyWindowBlur(overlay ?: return true, lastBlurRatio)

        if (per >= 0.6f && !armed) {
            armed = true
            vibrateFeedback(button.context)
            // 过半弹簧动画（与相机 startActiveAnim 一致，450ms）
            activeAnimator?.cancel()
            activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 450L
                interpolator = OvershootInterpolator(0.9f)
                addUpdateListener { activeAnimPer = it.animatedValue as Float }
            }
            activeAnimator?.start()
        }
        return true
    }

    private fun endDrag(button: View, event: MotionEvent): Boolean {
        val target = button
        val screenWidth = button.rootView.width.toFloat().coerceAtLeast(1f)
        val finalDx = event.rawX - downX
        val finalUp = (downY - event.rawY).coerceAtLeast(0f)
        val finalPer = maxOf(abs(finalDx), finalUp) / (screenWidth / 3f)
        val reached = finalPer >= 0.6f
        activeButton = null
        activeIcon = null
        activeAnimator?.cancel()
        activeAnimator = null
        activeAnimPer = 0f
        setSiblingsVisible(target, true)
        target.animate().alpha(1f).setDuration(120L).start()
        if (reached) {
            fireWithOverlay(target)
        } else {
            springBackOverlay(target)
        }
        return true
    }

    private fun cancelDrag(button: View): Boolean {
        activeButton = null
        activeIcon = null
        activeAnimator?.cancel()
        activeAnimator = null
        activeAnimPer = 0f
        setSiblingsVisible(button, true)
        button.animate().alpha(1f).setDuration(120L).start()
        springBackOverlay(button)
        return true
    }

    // 触发：覆盖窗口内的幽灵按钮从当前位置放大到覆盖全屏并渐隐，随后执行动作
    private fun fireWithOverlay(button: View) {
        val overlay = overlay
        val container = overlayContainer
        if (overlay == null || container == null) {
            dismissDragOverlay()
            performAction(button)
            return
        }
        val density = container.resources.displayMetrics.density
        val baseSize = (BUTTON_SIZE_DP * density).coerceAtLeast(1f)
        val maxDim = maxOf(overlay.width, overlay.height).toFloat()
        val fullScale = (maxDim * 1.5f / baseSize).coerceAtLeast(4f)
        container.animate()
            .scaleX(fullScale)
            .scaleY(fullScale)
            .alpha(0f)
            .setDuration(350L)
            .withEndAction {
                dismissDragOverlay()
                performAction(button)
            }
            .start()
    }

    // 未过半松手：图标沿原路慢慢回弹（250ms 过冲曲线），与相机 startActiveAnim(→0) 一致
    private fun springBackOverlay(button: View) {
        val container = overlayContainer
        if (container == null) {
            dismissDragOverlay()
            return
        }
        val dx = container.x - overlayStartCenterX + container.width / 2f
        val dy = container.y - overlayStartCenterY + container.height / 2f
        updateOverlayIconPosition(overlayStartCenterX, overlayStartCenterY)
        container.translationX = dx
        container.translationY = dy
        container.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(250L)
            .setInterpolator(OvershootInterpolator(0.9f))
            .withEndAction { dismissDragOverlay() }
            .start()
    }

    // ---------- 覆盖窗口 ----------

    private fun showDragOverlay(button: View) {
        removeOverlayNow()
        val context = button.context ?: return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density
        val iconSize = (ICON_SIZE_DP * density).toInt()

        val location = IntArray(2)
        button.getLocationOnScreen(location)
        val centerX = location[0] + button.width / 2f
        val centerY = location[1] + button.height / 2f
        overlayStartCenterX = centerX
        overlayStartCenterY = centerY

        val sourceIcon = activeIcon as? ImageView
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // 开启 = 不透明白；关闭 = 半透白
                setColor(if (isButtonActive(button)) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt())
            }
        }
        val icon = ImageView(context).apply {
            setImageDrawable(sourceIcon?.drawable)
            setColorFilter(sourceIcon?.colorFilter)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
        }
        container.addView(icon)

        val containerParams = FrameLayout.LayoutParams(
            (BUTTON_SIZE_DP * density).toInt(),
            (BUTTON_SIZE_DP * density).toInt(),
        ).apply {
            leftMargin = (centerX - BUTTON_SIZE_DP * density / 2f).toInt()
            topMargin = (centerY - BUTTON_SIZE_DP * density / 2f).toInt()
        }

        val overlay = FrameLayout(context).apply {
            setBackgroundColor(0x00000000)
        }
        overlay.addView(container, containerParams)

        val lp = WindowManager.LayoutParams().apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
            type = OVERLAY_WINDOW_TYPE
            flags = OVERLAY_WINDOW_FLAGS
            format = android.graphics.PixelFormat.TRANSLUCENT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            gravity = Gravity.TOP or Gravity.START
            title = "mixflipmod_lock_shortcut"
        }
        runCatching {
            val privateFlagsField = WindowManager.LayoutParams::class.java
                .getDeclaredField("privateFlags")
                .apply { isAccessible = true }
            privateFlagsField.setInt(lp, privateFlagsField.getInt(lp) or 0x20000000)
        }

        runCatching {
            wm.addView(overlay, lp)
            this.overlay = overlay
            this.overlayContainer = container
            this.overlayWm = wm
        }.onFailure { log("add shortcut overlay window failed", it) }
    }

    private fun updateOverlayIconPosition(centerX: Float, centerY: Float) {
        val container = overlayContainer ?: return
        val half = (BUTTON_SIZE_DP * container.resources.displayMetrics.density) / 2f
        container.layoutParams = (container.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = (centerX - half).toInt()
            topMargin = (centerY - half).toInt()
        }
    }

    private fun dismissDragOverlay() {
        blurAnimator?.cancel()
        val overlay = overlay
        val wm = overlayWm
        if (overlay != null && wm != null) {
            // 模糊渐进回 0（与出现时对称），动画结束后再摘窗口
            val animator = ValueAnimator.ofFloat(lastBlurRatio.coerceIn(0f, 1f), 0f).apply {
                duration = 200L
                addUpdateListener { applyWindowBlur(overlay, it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        runCatching { wm.removeView(overlay) }
                        clearOverlayRefs()
                    }
                })
            }
            blurAnimator = animator
            animator.start()
        } else {
            clearOverlayRefs()
        }
    }

    private fun removeOverlayNow() {
        blurAnimator?.cancel()
        blurAnimator = null
        val overlay = overlay
        val wm = overlayWm
        clearOverlayRefs()
        if (overlay != null && wm != null) {
            runCatching { wm.removeView(overlay) }
        }
    }

    private fun clearOverlayRefs() {
        blurAnimator = null
        overlay = null
        overlayContainer = null
        overlayWm = null
        overlayStartCenterX = 0f
        overlayStartCenterY = 0f
        lastBlurRatio = 0f
    }

    // ---------- 其他 ----------

    private fun findShortcutAt(event: MotionEvent): View? {
        val row = rowRef?.get() ?: return null
        if (row.width == 0 || row.height == 0) return null
        val location = IntArray(2)
        row.getLocationOnScreen(location)
        val rect = Rect(location[0], location[1], location[0] + row.width, location[1] + row.height)
        if (!rect.contains(event.rawX.toInt(), event.rawY.toInt())) return null
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i) ?: continue
            val childLocation = IntArray(2)
            child.getLocationOnScreen(childLocation)
            val childRect = Rect(
                childLocation[0],
                childLocation[1],
                childLocation[0] + child.width,
                childLocation[1] + child.height,
            )
            if (childRect.contains(event.rawX.toInt(), event.rawY.toInt())) return child
        }
        return null
    }

    private fun isOnCameraCircle(event: MotionEvent): Boolean {
        val cameraIcon = cameraIconRef?.get() ?: return false
        if (cameraIcon.width == 0 || cameraIcon.height == 0) return false
        val location = IntArray(2)
        cameraIcon.getLocationOnScreen(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + cameraIcon.width,
            location[1] + cameraIcon.height,
        )
        return rect.contains(event.rawX.toInt(), event.rawY.toInt())
    }

    // 只淡出/淡入我们自己的按钮（不动相机图标）
    private fun fadeRowOnly(visible: Boolean) {
        val row = rowRef?.get() ?: return
        val alpha = if (visible) 1f else 0f
        for (i in 0 until row.childCount) {
            row.getChildAt(i)?.animate()?.alpha(alpha)?.setDuration(120L)?.start()
        }
    }

    private fun setSiblingsVisible(active: View?, visible: Boolean) {
        val row = rowRef?.get() ?: return
        val alpha = if (visible) 1f else 0f
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i) ?: continue
            if (child === active) continue
            child.animate().alpha(alpha).setDuration(120L).start()
        }
        cameraIconRef?.get()?.animate()?.alpha(alpha)?.setDuration(120L)?.start()
    }

    private fun isButtonActive(button: View): Boolean {
        val row = rowRef?.get() ?: return false
        val index = row.indexOfChild(button)
        if (index < 0 || index >= shortcuts.size) return false
        return shortcuts[index].isActive(button.context)
    }

    private fun performAction(button: View) {
        val index = rowRef?.get()?.indexOfChild(button) ?: -1
        if (index in shortcuts.indices) {
            shortcuts[index].action(button.context)
        }
        Handler(Looper.getMainLooper()).post { refreshButtonStates() }
    }

    // 手电筒权威状态：Settings.Global torch_state（MiuiFlashlightControllerImpl.dispatchModeChanged 写入）
    private fun isTorchOn(): Boolean {
        val context = rowRef?.get()?.context ?: return torchOn
        return runCatching {
            Settings.Global.getInt(context.contentResolver, "torch_state", 0) == 1
        }.getOrDefault(torchOn)
    }

    private fun isMuted(context: Context): Boolean {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
        val mode = audioManager.ringerMode
        // 与控制中心静音磁贴一致：铃声模式为静音或振动都算"已静音"
        return mode == AudioManager.RINGER_MODE_SILENT || mode == AudioManager.RINGER_MODE_VIBRATE
    }

    // 与控制中心磁贴逐行一致（MiuiFlashlightTile.handleClick）：
    // 已开启 → 直接 setFlashlight(false)；未开启（小屏）→ 显示翻转手机引导弹窗 + setFlipListening(true)
    private fun toggleTorch(context: Context) {
        val controller = flashlightController
        if (controller != null) {
            val on = runCatching { controller.callMethod("isEnabled") as? Boolean }
                .getOrNull() ?: isTorchOn()
            if (on) {
                runCatching {
                    controller.callMethod("setFlashlight", false)
                }.onFailure { log("set flashlight failed", it) }
            } else {
                showFlipGuide(controller)
            }
            return
        }
        // 控制器不可用时的兜底：直接走 CameraManager（后置带闪光相机）
        runCatching {
            val manager = context.getSystemService(CameraManager::class.java) ?: return@runCatching
            val cameraId = torchCameraId ?: manager.cameraIdList.firstOrNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }?.also { torchCameraId = it } ?: return@runCatching
            val target = !isTorchOn()
            manager.setTorchMode(cameraId, target)
            torchOn = target
            Settings.Global.putInt(context.contentResolver, "torch_state", if (target) 1 else 0)
        }.onFailure { log("toggle torch failed", it) }
    }

    // 与控制中心一致的翻转向导：post Lambda0(controller, 2)（显示 mFlipDialog）+ setFlipListening(true)
    private fun showFlipGuide(controller: Any) {
        runCatching {
            val classLoader = controller.javaClass.classLoader
            val lambdaClass = Class.forName(
                "com.android.systemui.controlcenter.policy.MiuiFlashlightControllerImpl" +
                    "\$\$ExternalSyntheticLambda0",
                false,
                classLoader,
            )
            val lambda = lambdaClass
                .getConstructor(controller.javaClass, Int::class.javaPrimitiveType!!)
                .newInstance(controller, 2)
            val handler = runCatching { controller.getField("mHandler") as? Handler }.getOrNull()
            handler?.post(lambda as Runnable)
            controller.callMethod("setFlipListening", true)
        }.onFailure { log("show flip guide failed", it) }
    }

    // 与控制中心静音磁贴完全一致：miui.util.AudioManagerHelper.toggleSilent(ctx, 4)
    private fun toggleMute(context: Context) {
        val ok = runCatching {
            val helperClass = Class.forName("miui.util.AudioManagerHelper", false, context.classLoader)
            helperClass.getMethod(
                "toggleSilent",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
            ).invoke(null, context, 4)
            true
        }.getOrDefault(false)
        if (ok) return
        // 兜底：直接切铃声模式
        runCatching {
            val audioManager = context.getSystemService(AudioManager::class.java) ?: return@runCatching
            audioManager.ringerMode =
                if (isMuted(context)) AudioManager.RINGER_MODE_NORMAL
                else AudioManager.RINGER_MODE_SILENT
        }.onFailure { log("toggle mute failed", it) }
    }

    // 与相机一致的触感：MIUI IHapticFeedBack mesh_heavy（过半同款），失败回落系统震动
    private fun vibrateFeedback(context: Context) {
        val miuiOk = runCatching {
            val classLoader = context.classLoader
            val iface = classLoader.loadClass("com.miui.interfaces.IHapticFeedBack")
            val manager = classLoader.loadClass("miui.interfaces.InterfacesImplManager")
            val impl = manager.getMethod("getImpl", Class::class.java).invoke(null, iface)
                ?: return@runCatching false
            impl.javaClass.getMethod(
                "extExtHapticFeedback",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                String::class.java,
                Int::class.javaPrimitiveType!!,
                String::class.java,
            ).invoke(impl, 0, -1, "mesh_heavy", 0, null)
            true
        }.getOrDefault(false)
        if (miuiOk) return
        runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return@runCatching
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 50, 30), -1))
        }.onFailure { log("shortcut vibrate failed", it) }
    }

    private fun registerTorchStateObserver(context: Context) {
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor("torch_state"),
                false,
                object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        refreshButtonStates()
                    }
                },
            )
        }.onFailure { log("register torch state observer failed", it) }
    }
}
