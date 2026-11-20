package com.gatsmod.cut
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
class MainActivity:AppCompatActivity(){
 private var player:ExoPlayer?=null
 private lateinit var timeline:TimelineView
 private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:SecurityException){};loadVideo(uri)}}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_main);player=ExoPlayer.Builder(this).build().also{findViewById<PlayerView>(R.id.playerView).player=it};timeline=findViewById(R.id.timeline);timeline.onSeek={player?.seekTo(it)}
 findViewById<Button>(R.id.importButton).setOnClickListener{picker.launch(arrayOf("video/*"))}
 findViewById<Button>(R.id.splitButton).setOnClickListener{timeline.split();info("Split updated in timeline model (rendering not yet implemented).")}
 findViewById<Button>(R.id.trimStartButton).setOnClickListener{timeline.trimStart();info("Selected segment start adjusted in timeline model.")}
 findViewById<Button>(R.id.trimEndButton).setOnClickListener{timeline.trimEnd();info("Selected segment end adjusted in timeline model.")}
 findViewById<Button>(R.id.zoomInButton).setOnClickListener{timeline.zoomIn()}
 findViewById<Button>(R.id.zoomOutButton).setOnClickListener{timeline.zoomOut()}
 findViewById<Button>(R.id.speedButton).setOnClickListener{val p=player?:return@setOnClickListener;val n=if(p.playbackParameters.speed>=1.9f)1f else p.playbackParameters.speed+.5f;p.setPlaybackSpeed(n);Toast.makeText(this,"Preview speed: ${n}x",Toast.LENGTH_SHORT).show()}
 findViewById<Button>(R.id.exportButton).setOnClickListener{info("Export is not implemented. Timeline edits are not yet rendered into output video.")}
 }
 private fun loadVideo(uri:Uri){val p=player?:return;p.setMediaItem(MediaItem.fromUri(uri));p.prepare();p.playWhenReady=true;findViewById<TextView>(R.id.emptyPreview).visibility=android.view.View.GONE;p.addListener(object:androidx.media3.common.Player.Listener{override fun onPlaybackStateChanged(state:Int){if(state==androidx.media3.common.Player.STATE_READY&&timeline.durationMs==0L)timeline.setDuration(p.duration.takeIf{it>0}?:1L)}});findViewById<Button>(R.id.exportButton).isEnabled=true;info("Video loaded")}
 private fun info(s:String){findViewById<TextView>(R.id.clipInfo).text=s}
 private fun show(t:String,m:String){androidx.appcompat.app.AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK",null).show()}
 override fun onStop(){super.onStop();player?.pause()}
 override fun onDestroy(){player?.release();player=null;super.onDestroy()}
}