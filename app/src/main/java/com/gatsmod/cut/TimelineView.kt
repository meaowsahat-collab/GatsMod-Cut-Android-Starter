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
data class TimelineClip(var startMs:Long,var endMs:Long,var label:String="Video")
class TimelineView(context:Context,attrs:AttributeSet?=null):View(context,attrs){
 private val bg=Paint().apply{color=0xFF171D19.toInt()}
 private val clip=Paint().apply{color=0xFF277B52.toInt()}
 private val outline=Paint().apply{color=0xFF7CE6B0.toInt();style=Paint.Style.STROKE;strokeWidth=4f}
 private val play=Paint().apply{color=0xFFFFD166.toInt();strokeWidth=4f}
 private val txt=Paint().apply{color=0xFFFFFFFF.toInt();textSize=30f}
 private val grid=Paint().apply{color=0xFF39433D.toInt();strokeWidth=1f}
 var durationMs=0L;var playheadMs=0L;var clips= mutableListOf<TimelineClip>();var zoom=1f;var selectedIndex=0
 var onSeek:((Long)->Unit)?=null
 fun setDuration(d:Long){durationMs=max(1L,d);clips=mutableListOf(TimelineClip(0,durationMs));playheadMs=0;selectedIndex=0;invalidate()}
 fun split(){val c=clips.getOrNull(selectedIndex)?:return;if(playheadMs<=c.startMs+100||playheadMs>=c.endMs-100)return;val second=TimelineClip(playheadMs,c.endMs,c.label);c.endMs=playheadMs;clips.add(selectedIndex+1,second);selectedIndex++;invalidate()}
 fun trimStart(){clips.getOrNull(selectedIndex)?.let{it.startMs=min(playheadMs,it.endMs-100);invalidate()}}
 fun trimEnd(){clips.getOrNull(selectedIndex)?.let{it.endMs=max(playheadMs,it.startMs+100);invalidate()}}
 fun zoomIn(){zoom=min(4f,zoom*1.25f);invalidate()}
 fun zoomOut(){zoom=max(.5f,zoom/1.25f);invalidate()}
 override fun onDraw(canvas:Canvas){super.onDraw(canvas);canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),bg);val left=70f;val right=width-24f;val tw=max(1f,right-left);for(i in 0..4)canvas.drawLine(left+tw*i/4,0f,left+tw*i/4,height.toFloat(),grid);if(durationMs<=0){canvas.drawText("Import video to edit",20f,50f,txt);return};val vis=durationMs/zoom;val ppm=tw/vis;val offset=max(0f,(playheadMs*ppm-tw/2));canvas.drawText("VIDEO",12f,28f,txt);clips.forEachIndexed{idx,c->val x1=left+c.startMs*ppm-offset;val x2=left+c.endMs*ppm-offset;if(x2>=left&&x1<=right){val r=RectF(max(left,x1),40f,min(right,x2),height-18f);canvas.drawRoundRect(r,12f,12f,clip);if(idx==selectedIndex)canvas.drawRoundRect(r,12f,12f,outline);canvas.drawText("Clip ${idx+1}",r.left+10f,r.centerY(),txt)}};val x=(left+playheadMs*ppm-offset).coerceIn(left,right);canvas.drawLine(x,10f,x,height.toFloat(),play);canvas.drawCircle(x,10f,8f,play)}
 override fun onTouchEvent(e:MotionEvent):Boolean{if(durationMs<=0)return false;if(e.action==MotionEvent.ACTION_DOWN||e.action==MotionEvent.ACTION_MOVE){val left=70f;val tw=max(1f,width-94f);val ppm=tw/(durationMs/zoom);val off=max(0f,(playheadMs*ppm-tw/2));playheadMs=((e.x-left+off)/ppm).toLong().coerceIn(0,durationMs);if(e.action==MotionEvent.ACTION_DOWN){clips.indexOfFirst{playheadMs in it.startMs..it.endMs}.takeIf{it>=0}?.let{selectedIndex=it}};onSeek?.invoke(playheadMs);invalidate();return true};return true}
}