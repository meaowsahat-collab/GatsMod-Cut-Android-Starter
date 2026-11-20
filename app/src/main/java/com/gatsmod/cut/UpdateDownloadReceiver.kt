package com.gatsmod.cut

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

class UpdateDownloadReceiver: BroadcastReceiver() {
 override fun onReceive(context:Context,intent:Intent) {
  if(intent.action!=DownloadManager.ACTION_DOWNLOAD_COMPLETE)return
  val id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1L)
  val expected=context.getSharedPreferences("updater",0).getLong("download_id",-2L)
  if(id!=expected)return
  val dm=context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
  val uri=dm.getUriForDownloadedFile(id) ?: return
  val install=Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
  if(Build.VERSION.SDK_INT>=26 && !context.packageManager.canRequestPackageInstalls()){
    val settings=Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(settings);return
  }
  context.startActivity(install)
 }
}
