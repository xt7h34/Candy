package com.candy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/*
 * App updates. Candy asks GitHub for the newest release of its own repository (it has to be a public
 * one), and if there is a newer version it downloads the APK, checks it and hands it to Android's
 * installer. Nothing about the person is sent: it is one plain request for a public page.
 */

internal class UpdateInfo(
    val code: Int,
    val name: String,
    val notes: String,
    val page: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

internal const val UPD_NEW = 0
internal const val UPD_UP_TO_DATE = 1
internal const val UPD_NOT_CONFIGURED = 2
internal const val UPD_FAILED = 3

internal class CheckResult(val kind: Int, val info: UpdateInfo?)

internal object UpdatePrefs {
    private const val FILE = "candy_update"
    private const val DAY = 24L * 60 * 60 * 1000

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun auto(c: Context): Boolean = sp(c).getBoolean("auto", true)

    fun setAuto(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("auto", on).apply()
    }

    fun repo(c: Context): String = BuildConfig.UPDATE_REPO

    fun skipped(c: Context): Int = sp(c).getInt("skipped", 0)

    fun skip(c: Context, code: Int) {
        sp(c).edit().putInt("skipped", code).apply()
    }

    /** It is time for the automatic look when the last one was more than about a day ago. */
    fun isDue(c: Context): Boolean = System.currentTimeMillis() - sp(c).getLong("last", 0L) > DAY - 4 * 3_600_000L

    fun markChecked(c: Context, info: UpdateInfo?) {
        val e = sp(c).edit().putLong("last", System.currentTimeMillis())
        if (info != null) {
            e.putString(
                "info",
                JSONObject()
                    .put("code", info.code).put("name", info.name).put("notes", info.notes)
                    .put("page", info.page).put("url", info.url).put("size", info.size)
                    .put("sha", info.sha256 ?: "")
                    .toString(),
            )
        }
        e.apply()
    }

    /** The newest release seen at the last check. */
    fun cached(c: Context): UpdateInfo? {
        val raw = sp(c).getString("info", null) ?: return null
        return try {
            val o = JSONObject(raw)
            UpdateInfo(
                o.getInt("code"), o.getString("name"), o.getString("notes"), o.getString("page"),
                o.getString("url"), o.getLong("size"), o.optString("sha").takeIf { it.length == 64 },
            )
        } catch (e: Exception) {
            null
        }
    }
}

internal object Updater {
    private val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun installedCode(c: Context): Int {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(pi).toInt()
    }

    fun installedName(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: ""

    /** Looks for a newer version. */
    suspend fun check(c: Context): CheckResult {
        val repo = UpdatePrefs.repo(c)
        if (!REPO.matches(repo)) return CheckResult(UPD_NOT_CONFIGURED, null)
        return try {
            val info = fetchLatest(repo)
            UpdatePrefs.markChecked(c, info)
            if (info != null && info.code > installedCode(c)) CheckResult(UPD_NEW, info) else CheckResult(UPD_UP_TO_DATE, info)
        } catch (e: Exception) {
            CheckResult(UPD_FAILED, null)
        }
    }

    /** The newest release, or null when the repository has no release yet. */
    private suspend fun fetchLatest(repo: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "CandyApp")
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            parse(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    /** The release is named build-NUMBER, and the number is compared with this app's version code. */
    fun parse(json: String): UpdateInfo? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val url = a.optString("browser_download_url")
            if (a.optString("name").endsWith(".apk") && url.startsWith("https://")) {
                return UpdateInfo(
                    code = code,
                    name = o.optString("name").ifBlank { tag },
                    notes = o.optString("body"),
                    page = o.optString("html_url"),
                    url = url,
                    size = a.optLong("size"),
                    sha256 = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
                )
            }
        }
        return null
    }

    /**
     * Downloads the APK into the app's own cache and checks it: the checksum GitHub published,
     * that it is this same app, and that it is newer. A file that fails is deleted.
     */
    suspend fun download(c: Context, info: UpdateInfo, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(c.cacheDir, "updates")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "candy-${info.code}.apk")
            val conn = URL(info.url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "CandyApp")
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.size
                val md = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var read = input.read(buf)
                        var done = 0L
                        var last = 0L
                        while (read != -1) {
                            os.write(buf, 0, read)
                            md.update(buf, 0, read)
                            done += read
                            val now = System.nanoTime()
                            if (now - last > 100_000_000L) {
                                last = now
                                onProgress(done, total)
                            }
                            read = input.read(buf)
                        }
                        onProgress(done, total)
                    }
                }
                val sum = md.digest().joinToString("") { "%02x".format(it) }
                if (info.sha256 != null && !sum.equals(info.sha256, ignoreCase = true)) {
                    out.delete()
                    throw IOException("checksum")
                }
                val pi = c.packageManager.getPackageArchiveInfo(out.absolutePath, 0)
                if (pi == null || pi.packageName != c.packageName ||
                    PackageInfoCompat.getLongVersionCode(pi) <= installedCode(c)
                ) {
                    out.delete()
                    throw IOException("not a newer version of this app")
                }
                out
            } finally {
                conn.disconnect()
            }
        }

    fun canInstall(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()

    /** Opens the screen where the person allows this app to install apps. */
    fun openInstallPermission(c: Context) {
        c.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Hands the downloaded file to Android's installer, which asks the person to confirm. */
    fun install(c: Context, file: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.updates", file)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

// ───────────────────────── What the person sees ─────────────────────────

internal const val US_IDLE = 0
internal const val US_CHECKING = 1
internal const val US_UP_TO_DATE = 2
internal const val US_NEW = 3
internal const val US_DOWNLOADING = 4
internal const val US_READY = 5
internal const val US_FAILED = 6
internal const val US_NOT_CONFIGURED = 7
internal const val US_BAD_FILE = 8

/** Shared by the question and the settings section, so they always agree. */
internal object UpdateState {
    var status by mutableIntStateOf(US_IDLE)
    var info by mutableStateOf<UpdateInfo?>(null)
    var progress by mutableFloatStateOf(0f)
    var file by mutableStateOf<File?>(null)
    var needsPermission by mutableStateOf(false)

    /** "Later" was tapped. The question stays away until the app is opened again. */
    var postponed by mutableStateOf(false)

    suspend fun check(c: Context) {
        status = US_CHECKING
        val r = Updater.check(c)
        info = r.info
        status = when (r.kind) {
            UPD_NEW -> US_NEW
            UPD_UP_TO_DATE -> US_UP_TO_DATE
            UPD_NOT_CONFIGURED -> US_NOT_CONFIGURED
            else -> US_FAILED
        }
    }

    /** The automatic look when the app opens: at most about once a day. */
    suspend fun autoCheck(c: Context) {
        if (!UpdatePrefs.auto(c) || status != US_IDLE) return
        if (!UpdatePrefs.isDue(c)) {
            val cached = UpdatePrefs.cached(c)
            if (cached != null && cached.code > Updater.installedCode(c)) {
                info = cached
                status = US_NEW
            }
            return
        }
        check(c)
    }

    suspend fun downloadAndInstall(c: Context) {
        val i = info ?: return
        status = US_DOWNLOADING
        progress = 0f
        try {
            file = Updater.download(c, i) { done, total ->
                progress = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
            }
            status = US_READY
            install(c)
        } catch (e: Exception) {
            status = if (e.message == "checksum" || e.message?.startsWith("not a newer") == true) US_BAD_FILE else US_FAILED
        }
    }

    /** Opens Android's installer. The first time, the person must allow it in the settings. */
    fun install(c: Context) {
        val f = file ?: return
        if (!Updater.canInstall(c)) {
            needsPermission = true
            Updater.openInstallPermission(c)
            return
        }
        needsPermission = false
        Updater.install(c, f)
    }
}

private fun cleanNotes(raw: String): String =
    raw.replace(Regex("[#*`>]"), "").replace(Regex("\n{3,}"), "\n\n").trim()

private fun megabytes(size: Long): String = String.format("%.1f", size / 1048576.0)

/** The question when the app opens and a newer version is out. */
@Composable
internal fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { UpdateState.autoCheck(context) }

    val info = UpdateState.info
    val status = UpdateState.status
    val open = info != null && !UpdateState.postponed &&
        UpdatePrefs.skipped(context) != info.code &&
        (status == US_NEW || status == US_DOWNLOADING || status == US_READY || status == US_FAILED || status == US_BAD_FILE)
    if (!open || info == null) return

    AlertDialog(
        onDismissRequest = { if (status != US_DOWNLOADING) UpdateState.postponed = true },
        title = { Text(stringResource(R.string.upd_dialog_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.upd_dialog_text, info.name))
                if (info.size > 0) Text(stringResource(R.string.upd_size, megabytes(info.size)), style = MaterialTheme.typography.bodySmall)
                if (info.notes.isNotBlank()) {
                    Text(stringResource(R.string.upd_notes), style = MaterialTheme.typography.labelLarge)
                    Text(cleanNotes(info.notes), style = MaterialTheme.typography.bodySmall, maxLines = 12)
                }
                when (status) {
                    US_DOWNLOADING -> {
                        LinearProgressIndicator(progress = { UpdateState.progress }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.upd_downloading, (UpdateState.progress * 100).toInt()))
                    }
                    US_FAILED -> Text(stringResource(R.string.upd_failed), color = MaterialTheme.colorScheme.error)
                    US_BAD_FILE -> Text(stringResource(R.string.upd_bad_file), color = MaterialTheme.colorScheme.error)
                    US_READY -> if (UpdateState.needsPermission) Text(stringResource(R.string.upd_allow))
                }
            }
        },
        confirmButton = {
            when (status) {
                US_DOWNLOADING -> {}
                US_READY -> TextButton(onClick = { UpdateState.install(context) }) {
                    Text(stringResource(R.string.upd_install))
                }
                else -> TextButton(onClick = { scope.launch { UpdateState.downloadAndInstall(context) } }) {
                    Text(stringResource(R.string.upd_download))
                }
            }
        },
        dismissButton = {
            if (status != US_DOWNLOADING) {
                Row {
                    TextButton(
                        onClick = {
                            UpdatePrefs.skip(context, info.code)
                            UpdateState.postponed = true
                        },
                    ) { Text(stringResource(R.string.upd_skip)) }
                    TextButton(onClick = { UpdateState.postponed = true }) { Text(stringResource(R.string.upd_later)) }
                }
            }
        },
    )
}

/** On your profile: the installed version, a button to check by hand, and the new version if there is one. */
@Composable
internal fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(UpdatePrefs.auto(context)) }
    val status = UpdateState.status
    val info = UpdateState.info

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.upd_title))
            Text(
                stringResource(R.string.upd_current, Updater.installedName(context), Updater.installedCode(context)),
                style = MaterialTheme.typography.bodyMedium,
            )
            val line = when (status) {
                US_CHECKING -> stringResource(R.string.upd_checking)
                US_UP_TO_DATE -> stringResource(R.string.upd_uptodate)
                US_NEW, US_DOWNLOADING, US_READY -> stringResource(R.string.upd_new, info?.name ?: "")
                US_FAILED -> stringResource(R.string.upd_failed)
                US_BAD_FILE -> stringResource(R.string.upd_bad_file)
                US_NOT_CONFIGURED -> stringResource(R.string.upd_noconfig)
                else -> ""
            }
            if (line.isNotEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == US_FAILED || status == US_BAD_FILE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (info != null && (status == US_NEW || status == US_DOWNLOADING || status == US_READY)) {
                if (info.notes.isNotBlank()) {
                    Text(stringResource(R.string.upd_notes), style = MaterialTheme.typography.labelLarge)
                    Text(cleanNotes(info.notes), style = MaterialTheme.typography.bodySmall, maxLines = 12)
                }
                if (status == US_DOWNLOADING) {
                    LinearProgressIndicator(progress = { UpdateState.progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.upd_downloading, (UpdateState.progress * 100).toInt()))
                } else if (status == US_READY) {
                    if (UpdateState.needsPermission) Text(stringResource(R.string.upd_allow))
                    Button(onClick = { UpdateState.install(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.upd_install))
                    }
                } else {
                    Button(
                        onClick = { scope.launch { UpdateState.downloadAndInstall(context) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.upd_download)) }
                }
            }
            OutlinedButton(
                onClick = {
                    UpdateState.postponed = false
                    scope.launch { UpdateState.check(context) }
                },
                enabled = status != US_CHECKING && status != US_DOWNLOADING,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.upd_check)) }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.upd_auto))
                Switch(
                    checked = auto,
                    onCheckedChange = {
                        auto = it
                        UpdatePrefs.setAuto(context, it)
                    },
                )
            }
        }
    }
}
