package com.gatsmod.cut

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(32,32,32,32) }
        val status = TextView(this).apply { text = "Check GitHub for updates"; textSize = 18f }
        val check = Button(this).apply { text = "Check for Update" }
        root.addView(status); root.addView(check); setContentView(root)
        check.setOnClickListener {
            check.isEnabled = false; status.text = "Checking…"
            Thread {
                try {
                    val c = URL("https://api.github.com/repos/meaowsahat-collab/GatsMod-Cut-Android-Starter/releases/latest").openConnection() as HttpURLConnection
                    c.setRequestProperty("Accept", "application/vnd.github+json"); c.connectTimeout=10000; c.readTimeout=10000
                    if(c.responseCode!=200) throw Exception("GitHub response: ${c.responseCode}")
                    val release=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
                    c.disconnect()
                    val tag=release.optString("tag_name").removePrefix("v")
                    val asset=release.optJSONArray("assets")
                    var apkUrl:String?=null; var apkName=""
                    if(asset!=null) for(i in 0 until asset.length()) { val a=asset.getJSONObject(i); val n=a.optString("name"); if(n.endsWith(".apk",true)){apkUrl=a.optString("browser_download_url");apkName=n;break} }
                    val current=packageManager.getPackageInfo(packageName,0).versionName ?: "0"
                    runOnUiThread {
                        status.text="Installed: $current\nLatest Release: $tag"
                        if(compareVersions(tag,current)>0 && apkUrl!=null) {
                            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Update available").setMessage("Version $tag is available. Download and install update?").setPositiveButton("Update"){_,_->downloadApk(apkUrl!!,apkName)}.setNegativeButton("Later",null).show()
                        } else Toast.makeText(this,if(apkUrl==null)"Release APK not found" else "No update available",Toast.LENGTH_LONG).show()
                        check.isEnabled=true
                    }
                } catch(e:Exception){runOnUiThread{status.text="Update check failed: ${e.message}";check.isEnabled=true}}
            }.start()
        }
    }
    private fun compareVersions(a:String,b:String):Int {
        val x=a.split(".").map{it.toIntOrNull()?:0};val y=b.split(".").map{it.toIntOrNull()?:0}
        for(i in 0 until maxOf(x.size,y.size)){val p=x.getOrElse(i){0};val q=y.getOrElse(i){0};if(p!=q)return p.compareTo(q)};return 0
    }
    private fun downloadApk(url:String,name:String){
        try {
            val req=DownloadManager.Request(Uri.parse(url)).setTitle(name).setDescription("Downloading GatsMod Cut update").setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,name)
            val id=(getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            getSharedPreferences("updater",0).edit().putLong("download_id",id).apply()
            Toast.makeText(this,"Download started. When it finishes, open the download notification/file to install.",Toast.LENGTH_LONG).show()
        }catch(e:Exception){Toast.makeText(this,"Download failed: ${e.message}",Toast.LENGTH_LONG).show()}
    }
}
