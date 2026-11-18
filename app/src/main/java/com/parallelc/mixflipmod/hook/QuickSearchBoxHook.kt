package com.parallelc.mixflipmod.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import com.parallelc.mixflipmod.Prefs
import com.parallelc.mixflipmod.hook.util.findClass
import com.parallelc.mixflipmod.hook.util.hook
import com.parallelc.mixflipmod.hook.util.log
import com.parallelc.mixflipmod.hook.util.logInfo
import com.parallelc.mixflipmod.hook.util.method
import com.parallelc.mixflipmod.module
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.net.URLEncoder

/**
 * 全局搜索（com.android.quicksearchbox）的行为定制：
 * 键盘搜索键打开第一个高亮结果、网页搜索改走系统默认浏览器。
 *
 * 拦截点选在 TextView.onEditorAction（实测键盘搜索键只有这一层可拦）：
 * 应用发起 Activity 的路径不走 Instrumentation.execStartActivity，钩不到。
 */
object QuickSearchBoxHook : BaseHook() {
    override val targetPackages = listOf("com.android.quicksearchbox")

    private const val QSB_PACKAGE = "com.android.quicksearchbox"

    // 建议页的三层结构：页面列表 → 卡片 → 卡片内的横向结果列表
    private const val ID_SUGGESTION_PAGE = "rv_page_recycler_view"
    private const val ID_SUGGESTION_CARD = "rv_card_recycler_view"

    // 兜底网页搜索地址，与全局搜索当前使用的引擎一致（百度）
    private const val FALLBACK_SEARCH_URL = "https://m.baidu.com/s?word="

    private var installed = false
    private var openFirstResult = false
    private var searchWithDefaultBrowser = false

    override fun setupHooks(prefKey: String, param: PackageReadyParam) {
        if (prefKey != Prefs.QSB_SEARCH_KEY_OPEN_FIRST && prefKey != Prefs.QSB_SEARCH_DEFAULT_BROWSER) return
        installSearchHooks(param)
    }

    // 两个开关共用同一个拦截点，只安装一次
    private fun installSearchHooks(param: PackageReadyParam) {
        if (installed) return
        installed = true
        val prefs = module?.getRemotePreferences(Prefs.NAME)
        openFirstResult = prefs?.getBoolean(Prefs.QSB_SEARCH_KEY_OPEN_FIRST, false) ?: false
        searchWithDefaultBrowser = prefs?.getBoolean(Prefs.QSB_SEARCH_DEFAULT_BROWSER, false) ?: false
        logInfo("[qsb] search hooks installed: openFirst=$openFirstResult defaultBrowser=$searchWithDefaultBrowser")

        val textViewClass = param.classLoader.findClass("android.widget.TextView")
        hook(textViewClass.method("onEditorAction", Int::class.javaPrimitiveType!!)) { chain ->
            val actionId = chain.args[0] as? Int ?: return@hook chain.proceed()
            if (actionId != EditorInfo.IME_ACTION_SEARCH && actionId != EditorInfo.IME_ACTION_GO) {
                return@hook chain.proceed()
            }
            val input = chain.thisObject as? TextView ?: return@hook chain.proceed()
            if (handleSearchAction(input)) null else chain.proceed()
        }
    }

    // 返回 true 表示已接管，原生网页搜索不再执行
    private fun handleSearchAction(input: TextView): Boolean {
        val query = currentQuery(input)
        if (openFirstResult && clickFirstSuggestion(input.rootView)) {
            hideIme(input)
            return true
        }
        if (!searchWithDefaultBrowser) return false
        if (query.isEmpty()) return false
        return runCatching {
            val url = FALLBACK_SEARCH_URL + URLEncoder.encode(query, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val activity = findActivity(input.context)
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                // 非 Activity context 必须带 NEW_TASK，否则抛 AndroidRuntimeException
                input.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            hideIme(input)
            logInfo("[qsb] open browser fallback: $url")
            true
        }.getOrElse {
            log("[qsb] open search result with default browser failed", it)
            false
        }
    }

    // 查询词以 id 为准（qsb 的输入框 id 是 query_text），取不到再退回触发事件的那个 TextView
    private fun currentQuery(input: TextView): String {
        val root = input.rootView
        val id = root.resources.getIdentifier("query_text", "id", QSB_PACKAGE)
        val queryView = if (id != 0) runCatching { root.findViewById<TextView>(id) }.getOrNull() else null
        val fromId = queryView?.text?.toString()?.trim().orEmpty()
        val fromInput = input.text?.toString()?.trim().orEmpty()
        if (fromId.isNotEmpty()) return fromId
        if (fromInput.isNotEmpty()) return fromInput
        return firstNonEmptyEditText(root)
    }

    private fun firstNonEmptyEditText(view: View): String {
        if (view is TextView && view.javaClass.simpleName.contains("EditText")) {
            val text = view.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = firstNonEmptyEditText(view.getChildAt(i) ?: continue)
                if (found.isNotEmpty()) return found
            }
        }
        return ""
    }

    private fun clickFirstSuggestion(root: View): Boolean {
        val pageId = root.resources.getIdentifier(ID_SUGGESTION_PAGE, "id", QSB_PACKAGE)
        val cardId = root.resources.getIdentifier(ID_SUGGESTION_CARD, "id", QSB_PACKAGE)
        if (pageId == 0 || cardId == 0) return false
        val page = runCatching { root.findViewById<ViewGroup>(pageId) }.getOrNull() ?: return false

        // 第一个可点项可能不是真正的应用（可能是推广/去安装），先收集候选再挑
        val candidates = mutableListOf<Pair<View, List<String>>>()
        for (i in 0 until page.childCount) {
            val card = page.getChildAt(i) ?: continue
            val inner = runCatching { card.findViewById<ViewGroup>(cardId) }.getOrNull() ?: continue
            for (j in 0 until inner.childCount) {
                val item = inner.getChildAt(j) ?: continue
                if (item.visibility != View.VISIBLE) continue
                candidates += item to collectTexts(item)
            }
        }
        if (candidates.isEmpty()) return false
        val picked = pickCandidate(candidates) ?: return false
        return picked.first.performClick()
    }

    // 只认已安装应用：结果项标题命中了已安装应用名才算数；
    // 命中不到就返回 null，交给原生逻辑（不会误点“推荐安装”这类推广卡）
    private fun pickCandidate(candidates: List<Pair<View, List<String>>>): Pair<View, List<String>>? {
        val installedLabels by lazy { loadLauncherLabels(candidates.first().first) }
        val adLike = listOf("安装", "下载", "广告", "推荐")

        fun isApp(item: Pair<View, List<String>>): Boolean {
            if (item.second.any { text -> adLike.any { text.contains(it) } }) return false
            return item.second.any { text -> text.trim() in installedLabels }
        }

        return candidates.firstOrNull { isApp(it) }
    }

    private fun loadLauncherLabels(anchor: View): Set<String> = runCatching {
        val pm = anchor.context.packageManager
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { it.loadLabel(pm).toString() }.toSet()
    }.getOrDefault(emptySet())

    private fun collectTexts(view: View): List<String> {
        val texts = mutableListOf<String>()
        fun walk(current: View, depth: Int) {
            if (depth > 4) return
            if (current is TextView) {
                current.text.toString().trim().takeIf { it.isNotEmpty() }?.let { texts += it }
            }
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) {
                    walk(current.getChildAt(i) ?: continue, depth + 1)
                }
            }
        }
        walk(view, 0)
        return texts
    }

    private fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun hideIme(input: TextView) {
        runCatching {
            val imm = input.context.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
        }
    }
}
