package com.wang.sonovel.core

import android.content.Context
import com.wang.sonovel.BuildConfig
import com.wang.sonovel.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 服务器上的版本信息，对应 novel.json */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val forceUpdate: Boolean,
    val downloadUrl: String,
    val updateMessage: String,
)

sealed interface UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult
    data object Latest : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

/**
 * 版本更新检测：读取远程 JSON，versionCode 大于当前版本即视为有更新。
 */
class UpdateChecker(context: Context, private val settings: SettingsRepository) {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val _pending = MutableStateFlow<UpdateInfo?>(null)

    /** 启动检测发现、需要弹窗提示的新版本 */
    val pending: StateFlow<UpdateInfo?> = _pending.asStateFlow()

    @Volatile
    private var startupChecked = false

    /** 是否配置了更新地址（见 local.properties 的 update.url） */
    val enabled: Boolean get() = BuildConfig.UPDATE_URL.isNotBlank()

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext UpdateResult.Failed("未配置更新地址")
        runCatching {
            val page = Http.get(Http.client(settings.current), BuildConfig.UPDATE_URL, 10, mapOf("Cache-Control" to "no-cache"))
            if (page.code !in 200..299) throw IllegalStateException("HTTP ${page.code}")
            val o = JSONObject(String(page.bytes, Charsets.UTF_8))
            val info = UpdateInfo(
                versionCode = o.optInt("versionCode", 0),
                versionName = o.optString("versionName").ifBlank { o.optInt("versionCode").toString() },
                forceUpdate = o.optBoolean("forceUpdate", false),
                downloadUrl = o.optString("downloadUrl"),
                updateMessage = o.optString("updateMessage"),
            )
            if (info.versionCode > BuildConfig.VERSION_CODE) UpdateResult.Available(info) else UpdateResult.Latest
        }.getOrElse { UpdateResult.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** 启动时检测一次；网络失败时静默。已忽略的版本不再提示（强制更新除外） */
    suspend fun checkOnStartup() {
        if (startupChecked || !enabled) return
        startupChecked = true
        val r = check()
        if (r is UpdateResult.Available) {
            val ignored = prefs.getInt(KEY_IGNORED, 0)
            if (r.info.forceUpdate || r.info.versionCode != ignored) _pending.value = r.info
        }
    }

    /** 手动检查发现更新时同样弹出提示 */
    fun show(info: UpdateInfo) {
        _pending.value = info
    }

    fun dismiss() {
        _pending.value = null
    }

    fun ignore(info: UpdateInfo) {
        prefs.edit().putInt(KEY_IGNORED, info.versionCode).apply()
        _pending.value = null
    }

    companion object {
        private const val KEY_IGNORED = "ignored_version_code"
    }
}
