package com.gatsmod.cut

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater. Looks at the latest GitHub Release of this repo, and if its
 * version is newer than the installed one, downloads the APK and opens the
 * system installer. Because every release is signed with the same keystore,
 * Android installs it as an update over the old app (no uninstall needed).
 */
class Updater(private val activity: Activity) {

    private data class Release(val version: String, val notes: String, val apkUrl: String)

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    @Volatile private var cancelled = false
    private var pendingApk: File? = null
    private var skippedVersion: String? = null

    private val alive get() = !activity.isFinishing && !activity.isDestroyed

    /** Remove an APK left over from a previous update. */
    fun cleanup() {
        Thread { try { File(activity.cacheDir, "updates").deleteRecursively() } catch (_: Exception) {} }.start()
    }

    /** silent = true: only speak up when an update exists (used on app start). */
    fun check(silent: Boolean) {
        if (busy) return
        busy = true
        Thread {
            try {
                val rel = fetchLatest()
                val current = currentVersion()
                ui.post {
                    busy = false
                    if (!alive) return@post
                    when {
                        rel == null -> if (!silent) toast("No release found yet")
                        !isNewer(rel.version, current) -> if (!silent) toast("You are on the latest version ($current)")
                        silent && rel.version == skippedVersion -> Unit
                        else -> askToUpdate(rel, current)
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    busy = false
                    if (!silent && alive) toast("Update check failed: ${e.message}")
                }
            }
        }.start()
    }

    /** Call from onResume: continues the install after the user allowed "install unknown apps". */
    fun resumePendingInstall() {
        val f = pendingApk ?: return
        if (canInstall()) {
            pendingApk = null
            install(f)
        }
    }

    private fun currentVersion(): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"

    private fun fetchLatest(): Release? {
        val c = open("https://api.github.com/repos/$REPO/releases/latest")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw RuntimeException("GitHub HTTP ${c.responseCode}")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return null
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apk = a.getString("browser_download_url")
                    break
                }
            }
            if (apk == null) return null
            return Release(json.getString("tag_name"), json.optString("body", ""), apk)
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "GatsModCut-Updater")
        return c
    }

    private fun parts(v: String): List<Int> =
        Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun askToUpdate(rel: Release, current: String) {
        val notes = rel.notes.trim().take(700)
        val msg = "Installed: $current\nNew: ${rel.version}" + if (notes.isNotEmpty()) "\n\n$notes" else ""
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(msg)
            .setPositiveButton("Update now") { _, _ -> download(rel) }
            .setNegativeButton("Later") { _, _ -> skippedVersion = rel.version }
            .show()
    }

    private fun download(rel: Release) {
        cancelled = false
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = TextView(activity).apply { text = "Downloading ${rel.version}…" }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 32, 56, 8)
            addView(label)
            addView(bar)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Updating")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled = true }
            .show()

        Thread {
            val out = File(activity.cacheDir, "updates").apply { mkdirs() }.let { File(it, "update.apk") }
            try {
                val c = open(rel.apkUrl)
                try {
                    if (c.responseCode != 200) throw RuntimeException("Download HTTP ${c.responseCode}")
                    val total = c.contentLengthLong
                    c.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                if (cancelled) throw RuntimeException("cancelled")
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        ui.post {
                                            bar.isIndeterminate = false
                                            bar.progress = pct
                                            label.text = "Downloading ${rel.version}… $pct%"
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                ui.post {
                    if (alive) dialog.dismiss()
                    if (alive) install(out)
                }
            } catch (e: Exception) {
                out.delete()
                ui.post {
                    if (alive) dialog.dismiss()
                    if (alive && !cancelled) toast("Download failed: ${e.message}")
                }
            }
        }.start()
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()

    private fun install(apk: File) {
        if (!canInstall()) {
            pendingApk = apk
            AlertDialog.Builder(activity)
                .setTitle("Allow installing updates")
                .setMessage("Android needs your permission once so Get Cut can install its own updates. Turn it on, then come back to the app.")
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .setNegativeButton("Cancel") { _, _ -> pendingApk = null }
                .show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
        } catch (e: Exception) {
            toast("Could not open installer: ${e.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()

    companion object {
        const val REPO = "meaowsahat-collab/GatsMod-Cut-Android-Starter"
    }
}
