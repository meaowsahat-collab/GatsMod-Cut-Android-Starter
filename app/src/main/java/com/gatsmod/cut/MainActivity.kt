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
 private var pendingVideoUri:Uri?=null
 private val videoPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{persist(it);loadVideo(it,LaneKind.VIDEO)}}
 private val audioPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{persist(it);loadVideo(it,LaneKind.AUDIO)}}
 private fun persist(uri:Uri){try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:SecurityException){}}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_main)
 timeline=findViewById(R.id.timeline)
 player=ExoPlayer.Builder(this).build().also{p->findViewById<PlayerView>(R.id.playerView).player=p;p.addListener(object:androidx.media3.common.Player.Listener{override fun onPlaybackStateChanged(state:Int){if(state==androidx.media3.common.Player.STATE_READY){pendingVideoUri?.let{uri->val d=p.duration.takeIf{it>0}?:3000L;timeline.setDuration(maxOf(timeline.durationMs,d));timeline.addClip(LaneKind.VIDEO,"Video",uri.toString(),d);pendingVideoUri=null}}}})}
 timeline.onSeek={player?.seekTo(it)};timeline.onSelection={c->info(c?.let{"Selected: ${it.label} • ${it.kind} • ${it.startMs}–${it.endMs} ms"}?: "No clip selected")}
 findViewById<Button>(R.id.importButton).setOnClickListener{videoPicker.launch(arrayOf("video/*"))};findViewById<Button>(R.id.audioImportButton).setOnClickListener{audioPicker.launch(arrayOf("audio/*"))};findViewById<Button>(R.id.textClipButton).setOnClickListener{inputTextClip(LaneKind.TEXT)};findViewById<Button>(R.id.overlayButton).setOnClickListener{inputTextClip(LaneKind.OVERLAY)}
 findViewById<Button>(R.id.splitButton).setOnClickListener{timeline.split();info("Split applied")};findViewById<Button>(R.id.trimStartButton).setOnClickListener{timeline.trimStart();info("Trim start applied")};findViewById<Button>(R.id.trimEndButton).setOnClickListener{timeline.trimEnd();info("Trim end applied")};findViewById<Button>(R.id.duplicateButton).setOnClickListener{timeline.duplicate();info("Clip duplicated")};findViewById<Button>(R.id.deleteButton).setOnClickListener{timeline.delete();info("Clip deleted")};findViewById<Button>(R.id.mergeButton).setOnClickListener{timeline.merge();info("Merge only applies to adjacent clips on the selected track")}
 findViewById<Button>(R.id.nudgeLeftButton).setOnClickListener{timeline.nudgeFrames(-1);info("Moved one frame left")};findViewById<Button>(R.id.nudgeRightButton).setOnClickListener{timeline.nudgeFrames(1);info("Moved one frame right")};findViewById<Button>(R.id.undoButton).setOnClickListener{timeline.undo();info("Undo")};findViewById<Button>(R.id.redoButton).setOnClickListener{timeline.redo();info("Redo")};findViewById<Button>(R.id.zoomInButton).setOnClickListener{timeline.zoomIn()};findViewById<Button>(R.id.zoomOutButton).setOnClickListener{timeline.zoomOut()};findViewById<Button>(R.id.addVideoLaneButton).setOnClickListener{timeline.addLane(LaneKind.VIDEO)};findViewById<Button>(R.id.addAudioLaneButton).setOnClickListener{timeline.addLane(LaneKind.AUDIO)}
 findViewById<Button>(R.id.speedButton).setOnClickListener{val p=player?:return@setOnClickListener;val n=if(p.playbackParameters.speed>=1.9f)1f else p.playbackParameters.speed+.5f;p.setPlaybackSpeed(n);Toast.makeText(this,"Preview speed: ${n}x",Toast.LENGTH_SHORT).show()}
 findViewById<Button>(R.id.exportButton).setOnClickListener{info("Export/rendering is not implemented yet; edits currently affect the timeline model.")}
 }
 private fun inputTextClip(kind:LaneKind){val edit=android.widget.EditText(this);edit.hint=if(kind==LaneKind.TEXT)"Text clip content" else "Overlay label";androidx.appcompat.app.AlertDialog.Builder(this).setTitle(if(kind==LaneKind.TEXT)"Add text clip" else "Add overlay").setView(edit).setPositiveButton("Add"){_,_->val value=edit.text.toString().trim();if(value.isNotEmpty())timeline.addClip(kind,value,duration=3000L);info("Added ${kind.name.lowercase()} clip")}.setNegativeButton("Cancel",null).show()}
 private fun loadVideo(uri:Uri,kind:LaneKind){val p=player?:return;if(kind==LaneKind.VIDEO){pendingVideoUri=uri;p.setMediaItem(MediaItem.fromUri(uri));p.prepare();p.playWhenReady=true;findViewById<TextView>(R.id.emptyPreview).visibility=android.view.View.GONE}else timeline.addClip(LaneKind.AUDIO,"Audio",uri.toString(),timeline.durationMs.coerceAtLeast(3000L));findViewById<Button>(R.id.exportButton).isEnabled=true;info("${kind.name.lowercase()} imported")}
 private fun info(s:String){findViewById<TextView>(R.id.clipInfo).text=s}
 override fun onStop(){super.onStop();player?.pause()}
 override fun onDestroy(){player?.release();player=null;super.onDestroy()}
}