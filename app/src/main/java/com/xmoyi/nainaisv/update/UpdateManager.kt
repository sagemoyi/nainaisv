package com.xmoyi.nainaisv.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.xmoyi.nainaisv.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val publishedAt: String,
    val releaseNotes: String,
) {
    companion object {
        fun parse(json: String): UpdateManifest {
            val value = JSONObject(json)
            return UpdateManifest(
                versionCode = value.getInt("versionCode"),
                versionName = value.getString("versionName"),
                minSupportedVersionCode = value.optInt("minSupportedVersionCode", 1),
                apkUrl = value.getString("apkUrl"),
                sha256 = value.getString("sha256").lowercase(),
                size = value.getLong("size"),
                publishedAt = value.optString("publishedAt"),
                releaseNotes = value.optString("releaseNotes"),
            ).also {
                require(it.apkUrl.startsWith("https://")) { "更新地址必须使用 HTTPS" }
                require(it.sha256.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 格式无效" }
                require(it.size > 0) { "APK 大小无效" }
            }
        }
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateState
    data class ReadyToInstall(val manifest: UpdateManifest, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

class UpdateManager(
    private val application: Application,
    private val bilibiliHttpClient: OkHttpClient,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    suspend fun check(manifestUrl: String) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        runCatching {
            val request = Request.Builder()
                .url(manifestUrl)
                .header("Cache-Control", "no-cache, max-age=0")
                .build()
            val body = bilibiliHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string() ?: throw IOException("更新清单为空")
            }
            UpdateManifest.parse(body)
        }.onSuccess { manifest ->
            _state.value = if (manifest.versionCode > BuildConfig.VERSION_CODE) {
                UpdateState.Available(manifest)
            } else {
                UpdateState.UpToDate
            }
        }.onFailure { error ->
            _state.value = UpdateState.Error(error.message ?: "检查更新失败")
        }
    }

    suspend fun download(manifest: UpdateManifest) = withContext(Dispatchers.IO) {
        runCatching {
            val updateDir = File(application.externalCacheDir ?: application.cacheDir, "updates")
            if (!updateDir.exists() && !updateDir.mkdirs()) throw IOException("无法创建更新目录")
            val target = File(updateDir, "nainaisv-${manifest.versionName}.apk")
            val request = Request.Builder().url(manifest.apkUrl).build()
            bilibiliHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败 HTTP ${response.code}")
                val body = response.body ?: throw IOException("APK 响应为空")
                val total = body.contentLength().takeIf { it > 0 } ?: manifest.size
                FileOutputStream(target).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            _state.value = UpdateState.Downloading(
                                manifest,
                                (downloaded.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
            if (target.length() != manifest.size) {
                target.delete()
                throw IOException("APK 文件大小校验失败")
            }
            val actualHash = sha256(target)
            if (!actualHash.equals(manifest.sha256, ignoreCase = true)) {
                target.delete()
                throw IOException("APK SHA-256 校验失败")
            }
            target
        }.onSuccess { file ->
            _state.value = UpdateState.ReadyToInstall(manifest, file)
        }.onFailure { error ->
            _state.value = UpdateState.Error(error.message ?: "下载更新失败")
        }
    }

    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !application.packageManager.canRequestPackageInstalls()
        ) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${application.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            application.startActivity(permissionIntent)
            return
        }
        val uri = FileProvider.getUriForFile(
            application,
            "${application.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        application.startActivity(intent)
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
