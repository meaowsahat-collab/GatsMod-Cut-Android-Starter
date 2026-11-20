package com.gatsmod.cut
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
enum class LaneKind { VIDEO, AUDIO, TEXT, OVERLAY }
data class TimelineClip(var startMs:Long,var endMs:Long,var label:String="Clip",var kind:LaneKind=LaneKind.VIDEO,var source:String="")
data class TimelineLane(val kind:LaneKind,var name:String,var clips:MutableList<TimelineClip> = mutableListOf())
class TimelineView(context:Context,attrs:AttributeSet?=null):View(context,attrs){
 private val bg=Paint().apply{color=0xFF171D19.toInt()};private val colors=mapOf(LaneKind.VIDEO to 0xFF277B52.toInt(),LaneKind.AUDIO to 0xFF3669A8.toInt(),LaneKind.TEXT to 0xFF9B6BC4.toInt(),LaneKind.OVERLAY to 0xFFB57932.toInt());private val fill=Paint();private val outline=Paint().apply{color=0xFF7CE6B0.toInt();style=Paint.Style.STROKE;strokeWidth=3f};private val play=Paint().apply{color=0xFFFFD166.toInt();strokeWidth=3f};private val txt=Paint().apply{color=0xFFFFFFFF.toInt();textSize=24f};private val grid=Paint().apply{color=0xFF39433D.toInt()}
 var durationMs=0L;var playheadMs=0L;var zoom=1f;var selectedLane=0;var selectedClip=0;var lanes= mutableListOf<TimelineLane>()
 private val undoStack=mutableListOf<List<TimelineLane>>();private val redoStack=mutableListOf<List<TimelineLane>>();var onSeek:((Long)->Unit)?=null;var onSelection:((TimelineClip?)->Unit)?=null;private var dragging=false;private var dragStartX=0f;private var originalStart=0L;private var dragSnapshot:List<TimelineLane>?=null;private val frameMs=33L
 init{lanes.add(TimelineLane(LaneKind.VIDEO,"Video 1"))}
 fun setDuration(d:Long){durationMs=max(1L,d);invalidate()}
 fun addClip(kind:LaneKind,label:String,source:String="",duration:Long=durationMs.coerceAtLeast(1000L)){saveUndo();val lane=lanes.indexOfFirst{it.kind==kind}.takeIf{it>=0}?:run{lanes.add(TimelineLane(kind,"$kind ${lanes.count{it.kind==kind}+1}"));lanes.lastIndex};val start=playheadMs;val len=max(1000L,duration);lanes[lane].clips.add(TimelineClip(start,start+len,label,kind,source));selectedLane=lane;selectedClip=lanes[lane].clips.lastIndex;durationMs=max(durationMs,start+len);invalidate()}
 fun addLane(kind:LaneKind){saveUndo();lanes.add(TimelineLane(kind,"${kind.name} ${lanes.count{it.kind==kind}+1}"));invalidate()}
 fun selected():TimelineClip?=lanes.getOrNull(selectedLane)?.clips?.getOrNull(selectedClip)
 fun split(){val c=selected()?:return;if(playheadMs<=c.startMs+frameMs||playheadMs>=c.endMs-frameMs)return;saveUndo();val tail=c.copy(startMs=playheadMs);c.endMs=playheadMs;lanes[selectedLane].clips.add(selectedClip+1,tail);selectedClip++;invalidate()}
 fun trimStart(){val c=selected()?:return;if(playheadMs>=c.endMs-frameMs)return;saveUndo();c.startMs=playheadMs;invalidate()}
 fun trimEnd(){val c=selected()?:return;if(playheadMs<=c.startMs+frameMs)return;saveUndo();c.endMs=playheadMs;invalidate()}
 fun duplicate(){val c=selected()?:return;saveUndo();val copy=c.copy(startMs=c.endMs,endMs=c.endMs+(c.endMs-c.startMs));lanes[selectedLane].clips.add(selectedClip+1,copy);selectedClip++;durationMs=max(durationMs,copy.endMs);invalidate()}
 fun delete(){if(selected()==null)return;saveUndo();lanes[selectedLane].clips.removeAt(selectedClip);selectedClip=(selectedClip-1).coerceAtLeast(0);invalidate()}
 fun merge(){val list=lanes.getOrNull(selectedLane)?.clips?:return;if(selectedClip+1>=list.size)return;val a=list[selectedClip];val b=list[selectedClip+1];if(a.kind!=b.kind||b.startMs>a.endMs+frameMs*2)return;saveUndo();a.endMs=max(a.endMs,b.endMs);list.removeAt(selectedClip+1);invalidate()}
 fun nudgeFrames(frames:Int){val c=selected()?:return;saveUndo();val d=frames*frameMs;c.startMs=max(0,c.startMs+d);c.endMs=max(c.startMs+frameMs,c.endMs+d);invalidate()}
 fun zoomIn(){zoom=min(8f,zoom*1.25f);invalidate()};fun zoomOut(){zoom=max(.5f,zoom/1.25f);invalidate()}
 fun undo(){if(undoStack.isEmpty())return;redoStack.add(snapshot());restore(undoStack.removeAt(undoStack.lastIndex)};fun redo(){if(redoStack.isEmpty())return;undoStack.add(snapshot());restore(redoStack.removeAt(redoStack.lastIndex))}
 private fun saveUndo(){undoStack.add(snapshot());if(undoStack.size>50)undoStack.removeAt(0);redoStack.clear()};private fun snapshot():List<TimelineLane> = lanes.map{TimelineLane(it.kind,it.name,it.clips.map{c->c.copy()}.toMutableList())};private fun restore(s:List<TimelineLane>){lanes=s.map{TimelineLane(it.kind,it.name,it.clips.map{c->c.copy()}.toMutableList())}.toMutableList();selectedLane=selectedLane.coerceIn(0,lanes.lastIndex);selectedClip=selectedClip.coerceAtLeast(0);invalidate()}
 override fun onDraw(canvas:Canvas){super.onDraw(canvas);canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),bg);val left=100f;val right=width-12f;val tw=max(1f,right-left);val scale=tw/(max(1000L,durationMs)/zoom);for(i in 0..8)canvas.drawLine(left+tw*i/8,0f,left+tw*i/8,height.toFloat(),grid);if(durationMs<=0){canvas.drawText("Import media to start",12f,40f,txt);return};lanes.forEachIndexed{li,lane->val top=li*70f+4f;canvas.drawText(lane.name.take(8),8f,top+28f,txt);lane.clips.forEachIndexed{ci,c->val x1=left+c.startMs*scale;val x2=left+c.endMs*scale;fill.color=colors[c.kind]?:0xFF277B52.toInt();val r=RectF(max(left,x1),top+8f,min(right,max(x1+14f,x2)),top+60f);canvas.drawRoundRect(r,8f,8f,fill);if(li==selectedLane&&ci==selectedClip)canvas.drawRoundRect(r,8f,8f,outline);canvas.drawText(c.label.take(11),r.left+5f,r.centerY()+8f,txt)}};val px=(left+playheadMs*scale).coerceIn(left,right);canvas.drawLine(px,0f,px,height.toFloat(),play)}
 override fun onTouchEvent(e:MotionEvent):Boolean{if(durationMs<=0)return false;val left=100f;val tw=max(1f,width-112f);val scale=tw/(max(1000L,durationMs)/zoom);when(e.action){MotionEvent.ACTION_DOWN->{dragStartX=e.x;val li=(e.y/70f).toInt().coerceIn(0,lanes.lastIndex);selectedLane=li;val lane=lanes[li];val t=((e.x-left)/scale).toLong().coerceAtLeast(0);val idx=lane.clips.indexOfFirst{t in it.startMs..it.endMs};if(idx>=0){selectedClip=idx;dragging=true;dragSnapshot=snapshot();originalStart=lane.clips[idx].startMs;onSelection?.invoke(lane.clips[idx])}else{playheadMs=t.coerceIn(0,durationMs);onSeek?.invoke(playheadMs)};invalidate();return true};MotionEvent.ACTION_MOVE->{if(dragging){val c=selected()?:return true;val delta=((e.x-dragStartX)/scale).toLong();c.startMs=max(0,originalStart+delta);c.endMs=max(c.startMs+frameMs,c.endMs+delta)}else{playheadMs=((e.x-left)/scale).toLong().coerceIn(0,durationMs);onSeek?.invoke(playheadMs)};invalidate();return true};MotionEvent.ACTION_UP->{if(dragging){dragSnapshot?.let{undoStack.add(it)};dragSnapshot=null;redoStack.clear();dragging=false}else{playheadMs=((e.x-left)/scale).toLong().coerceIn(0,durationMs);onSeek?.invoke(playheadMs)};invalidate();return true}};return true}
}
