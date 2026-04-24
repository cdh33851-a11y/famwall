package com.example.famwall

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class GitHubUpdateManager(
    private val context: Context,
    private val owner: String = BuildConfig.GITHUB_RELEASE_OWNER,
    private val repo: String = BuildConfig.GITHUB_RELEASE_REPO,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCurrentVersion(): String = BuildConfig.VERSION_NAME

    fun parseVersion(rawVersion: String?): SemVer? {
        if (rawVersion.isNullOrBlank()) {
            return null
        }

        val match = VERSION_REGEX.find(rawVersion.trim()) ?: return null
        return SemVer(
            major = match.groupValues[1].toIntOrNull() ?: return null,
            minor = match.groupValues[2].toIntOrNull() ?: 0,
            patch = match.groupValues[3].toIntOrNull() ?: 0,
        )
    }

    fun compareVersions(currentVersion: String, latestVersion: String): Int {
        val current = parseVersion(currentVersion) ?: return 0
        val latest = parseVersion(latestVersion) ?: return 0
        return current.compareTo(latest)
    }

    fun checkForUpdate(callback: UpdateCallback) {
        if (!isRepositoryConfigured()) {
            callback.onNoUpdate()
            return
        }

        callback.onChecking()
        thread(name = "FamWallUpdateCheck") {
            runCatching {
                val release = fetchLatestRelease()
                val latestVersion = parseVersion(release.version)?.toString()
                    ?: throw IllegalStateException("Release version not found.")
                val apkAsset = release.assets.firstOrNull { it.isAndroidApkForThisRepository() }
                    ?: throw IllegalStateException("Release APK asset not found.")

                preferences.edit()
                    .putString(KEY_LAST_RELEASE_VERSION, latestVersion)
                    .putString(KEY_LAST_RELEASE_ASSET, apkAsset.name)
                    .apply()

                if (compareVersions(getCurrentVersion(), latestVersion) < 0) {
                    post {
                        callback.onUpdateAvailable(
                            release.copy(version = latestVersion, assets = listOf(apkAsset)),
                        )
                    }
                } else {
                    post { callback.onNoUpdate() }
                }
            }.onFailure { exception ->
                Log.e(TAG, "Update check failed.", exception)
                post { callback.onError(exception) }
            }
        }
    }

    fun fetchLatestRelease(): GitHubRelease {
        val apiUrl = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
        val connection = (apiUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FamWall-Android/${getCurrentVersion()}")
        }

        connection.inputStream.bufferedReader().use { reader ->
            val body = reader.readText()
            val json = JSONObject(body)
            val releaseBody = json.optString("body", "")
            val tagName = json.optString("tag_name", "")
            val releaseName = json.optString("name", "")
            val parsedBody = parseReleaseBody(releaseBody)
            val version = parsedBody.version ?: tagName.ifBlank { releaseName }
            val assets = buildList {
                val assetArray = json.optJSONArray("assets")
                if (assetArray != null) {
                    for (index in 0 until assetArray.length()) {
                        val asset = assetArray.getJSONObject(index)
                        add(
                            GitHubReleaseAsset(
                                name = asset.optString("name", ""),
                                downloadUrl = asset.optString("browser_download_url", ""),
                                size = asset.optLong("size", 0L),
                            )
                        )
                    }
                }
            }

            return GitHubRelease(
                version = version,
                tagName = tagName,
                name = releaseName,
                body = releaseBody,
                changeLog = parsedBody.changeLog,
                forceUpdate = parsedBody.forceUpdate,
                assets = assets,
            )
        }
    }

    fun downloadUpdateFile(
        release: GitHubRelease,
        callback: DownloadCallback,
    ) {
        val asset = release.assets.firstOrNull { it.isAndroidApkForThisRepository() }
            ?: run {
                callback.onError(IllegalStateException("APK asset not found."))
                return
            }

        callback.onStarted(asset.name)
        thread(name = "FamWallUpdateDownload") {
            runCatching {
                val updateDirectory = File(context.cacheDir, UPDATE_DIRECTORY).apply { mkdirs() }
                val updateFile = File(updateDirectory, asset.name)
                val connection = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = DOWNLOAD_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "FamWall-Android/${getCurrentVersion()}")
                }

                val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: asset.size
                var downloadedBytes = 0L
                connection.inputStream.use { input ->
                    updateFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            val progress = if (totalBytes > 0L) {
                                ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            post { callback.onProgress(progress) }
                        }
                    }
                }

                if (!isValidDownloadedApk(updateFile, asset.name)) {
                    updateFile.delete()
                    throw IllegalStateException("Downloaded update file is invalid.")
                }

                preferences.edit().putString(KEY_LAST_DOWNLOADED_APK_PATH, updateFile.absolutePath).apply()
                post { callback.onCompleted(updateFile) }
            }.onFailure { exception ->
                Log.e(TAG, "Update download failed.", exception)
                post { callback.onError(exception) }
            }
        }
    }

    fun installUpdateFile(updateFile: File): Boolean {
        if (!isValidDownloadedApk(updateFile, updateFile.name)) {
            return false
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return false
        }

        val updateUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            updateFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(updateUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }

    private fun GitHubReleaseAsset.isAndroidApkForThisRepository(): Boolean {
        if (!name.lowercase(Locale.ROOT).endsWith(APK_EXTENSION)) {
            return false
        }

        val uri = runCatching { Uri.parse(downloadUrl) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host == "github.com" &&
            uri.path?.startsWith("/$owner/$repo/releases/download/") == true
    }

    private fun isValidDownloadedApk(file: File, expectedName: String): Boolean {
        return file.exists() &&
            file.isFile &&
            file.length() > 0L &&
            file.name == expectedName &&
            file.name.lowercase(Locale.ROOT).endsWith(APK_EXTENSION)
    }

    private fun parseReleaseBody(body: String): ParsedReleaseBody {
        var version: String? = null
        var forceUpdate = false
        val changeLogLines = mutableListOf<String>()

        body.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("version:", ignoreCase = true) ->
                    version = trimmed.substringAfter(":").trim()
                trimmed.startsWith("forceUpdate:", ignoreCase = true) ->
                    forceUpdate = trimmed.substringAfter(":").trim().equals("true", ignoreCase = true)
                trimmed.isNotBlank() && !trimmed.startsWith("version:", ignoreCase = true) &&
                    !trimmed.startsWith("forceUpdate:", ignoreCase = true) ->
                    changeLogLines.add(line)
            }
        }

        return ParsedReleaseBody(
            version = version,
            forceUpdate = forceUpdate,
            changeLog = changeLogLines.joinToString("\n").trim().ifBlank { body.trim() },
        )
    }

    private fun isRepositoryConfigured(): Boolean {
        return owner.isNotBlank() &&
            repo.isNotBlank() &&
            owner != "CHANGE_ME_OWNER" &&
            repo != "CHANGE_ME_REPO"
    }

    private fun post(action: () -> Unit) {
        mainHandler.post(action)
    }

    data class SemVer(
        val major: Int,
        val minor: Int,
        val patch: Int,
    ) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            return compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
        }

        override fun toString(): String = "$major.$minor.$patch"
    }

    data class GitHubRelease(
        val version: String,
        val tagName: String,
        val name: String,
        val body: String,
        val changeLog: String,
        val forceUpdate: Boolean,
        val assets: List<GitHubReleaseAsset>,
    )

    data class GitHubReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
    )

    data class ParsedReleaseBody(
        val version: String?,
        val forceUpdate: Boolean,
        val changeLog: String,
    )

    interface UpdateCallback {
        fun onChecking()
        fun onUpdateAvailable(release: GitHubRelease)
        fun onNoUpdate()
        fun onError(error: Throwable)
    }

    interface DownloadCallback {
        fun onStarted(fileName: String)
        fun onProgress(progress: Int)
        fun onCompleted(file: File)
        fun onError(error: Throwable)
    }

    companion object {
        private const val PREFS_NAME = "famwall_update_prefs"
        private const val KEY_LAST_RELEASE_VERSION = "last_release_version"
        private const val KEY_LAST_RELEASE_ASSET = "last_release_asset"
        private const val KEY_LAST_DOWNLOADED_APK_PATH = "last_downloaded_apk_path"
        private const val UPDATE_DIRECTORY = "updates"
        private const val APK_EXTENSION = ".apk"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val DOWNLOAD_TIMEOUT_MS = 60_000
        private const val TAG = "FamWallUpdater"
        private val VERSION_REGEX = Regex("""v?(\d+)(?:\.(\d+))?(?:\.(\d+))?""", RegexOption.IGNORE_CASE)
    }
}
