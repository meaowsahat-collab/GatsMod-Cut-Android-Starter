package com.gatsmod.cut

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class PhotoActivity : AppCompatActivity() {
    private var source: Bitmap? = null
    private lateinit var image: ImageView
    private lateinit var panel: LinearLayout
    private lateinit var categoryRow: LinearLayout
    private var category = "Light"
    private var rotation = 0f
    private val values = linkedMapOf("Exposure" to 0f, "Contrast" to 0f, "Highlights" to 0f, "Shadows" to 0f, "Whites" to 0f, "Blacks" to 0f, "Temperature" to 0f, "Tint" to 0f, "Vibrance" to 0f, "Saturation" to 0f, "Fade" to 0f)
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { load(it) } }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun text(s:String,size:Float=14f,color:Int=Color.WHITE):TextView=TextView(this).apply { text=s; textSize=size; setTextColor(color); gravity=Gravity.CENTER; setPadding(dp(8),dp(7),dp(8),dp(7)) }
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        window.statusBarColor=Color.BLACK; window.navigationBarColor=Color.BLACK
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xFF101114.toInt())}
        val top=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        top.addView(text("‹",26f).apply{setOnClickListener{finish()}},LinearLayout.LayoutParams(dp(48),dp(48)))
        top.addView(text("GatsMod Photo",17f),LinearLayout.LayoutParams(0,dp(48),1f))
        top.addView(text("Reset",12f,0xFF9EDFF2.toInt()).apply{setOnClickListener{reset()}},LinearLayout.LayoutParams(dp(56),dp(48)))
        top.addView(text("Export",15f,0xFF9EDFF2.toInt()).apply{setOnClickListener{save()}},LinearLayout.LayoutParams(dp(64),dp(48)))
        root.addView(top)
        image=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK)}
        root.addView(image,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        categoryRow=LinearLayout(this).apply{gravity=Gravity.CENTER;orientation=LinearLayout.HORIZONTAL}
        val scroller=android.widget.HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(categoryRow)}
        listOf("Light","Color","Effects","Detail","Crop","Presets").forEach{ name->categoryRow.addView(text(name,13f).apply{setOnClickListener{category=name;refreshPanel()}})}
        root.addView(scroller)
        panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(android.widget.ScrollView(this).apply{addView(panel)},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(230)))
        root.addView(text("Choose Photo",14f,0xFF80D8F2.toInt()).apply{setOnClickListener{picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)))
        setContentView(root);refreshPanel();picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    private fun load(uri:Uri){try{
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
        var sample=1; while(bounds.outWidth/sample>2048 || bounds.outHeight/sample>2048) sample*=2
        source=contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}
        rotation=0f;reset()
    }catch(e:Exception){Toast.makeText(this,"Could not open image",Toast.LENGTH_LONG).show()}}
    private fun keys():List<String> = when(category){
        "Light"->listOf("Exposure","Contrast","Highlights","Shadows","Whites","Blacks")
        "Color"->listOf("Temperature","Tint","Vibrance","Saturation")
        "Effects"->listOf("Fade")
        "Detail"->emptyList()
        else->emptyList()
    }
    private fun refreshPanel(){
        panel.removeAllViews()
        if(category=="Crop"){
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER}
            row.addView(text("Rotate −90°").apply{setOnClickListener{rotation-=90;updatePreview()}})
            row.addView(text("Rotate +90°").apply{setOnClickListener{rotation+=90;updatePreview()}})
            row.addView(text("Reset").apply{setOnClickListener{rotation=0f;updatePreview()}})
            panel.addView(row);panel.addView(text("Rotation controls active. Crop ratios are a future stage.",12f,0xFF9CA3AF.toInt()));return
        }
        if(category=="Presets"){
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER;orientation=LinearLayout.HORIZONTAL}
            listOf("Original","Warm","Cool","Mono","Pop").forEach{p->row.addView(text(p,12f).apply{setOnClickListener{applyPreset(p)}})}
            panel.addView(row);return
        }
        if(category=="Detail"){panel.addView(text("Sharpening and noise reduction are planned for a later stage.",12f,0xFF9CA3AF.toInt()));return}
        keys().forEach{k->
            val line=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
            line.addView(text(k,12f),LinearLayout.LayoutParams(dp(94),dp(44)))
            line.addView(text(String.format("%.0f",values[k]!!*100),11f,0xFFB0B0B0.toInt()),LinearLayout.LayoutParams(dp(42),dp(44)))
            val seek=SeekBar(this).apply{max=200;progress=(values[k]!!*100+100).toInt().coerceIn(0,200)}
            seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(s:SeekBar?,p:Int,from:Boolean){if(from){values[k]=(p-100)/100f;(line.getChildAt(1) as TextView).text=String.format("%.0f",values[k]!!*100);updatePreview()}}
                override fun onStartTrackingTouch(s:SeekBar?){}
                override fun onStopTrackingTouch(s:SeekBar?){}
            })
            line.addView(seek,LinearLayout.LayoutParams(0,dp(44),1f));panel.addView(line)
        }
    }
    private fun applyPreset(p:String){values.keys.forEach{values[it]=0f};when(p){"Warm"->{values["Temperature"]=0.35f;values["Vibrance"]=0.15f};"Cool"->{values["Temperature"]=-0.35f;values["Contrast"]=0.1f};"Mono"->{values["Saturation"]=-1f;values["Contrast"]=0.15f};"Pop"->{values["Contrast"]=0.2f;values["Vibrance"]=0.35f;values["Saturation"]=0.1f}};refreshPanel();updatePreview()}
    private fun matrix():ColorMatrix{
        val exp=values["Exposure"]!!*120f + values["Highlights"]!!*35f + values["Shadows"]!!*35f + values["Whites"]!!*30f + values["Blacks"]!!*30f;val contrast=(1f+values["Contrast"]!!*0.75f).coerceIn(0.25f,2f);val sat=(1f+values["Saturation"]!!).coerceIn(0f,2f);val vibrance=(1f+values["Vibrance"]!!*0.5f).coerceIn(0f,1.5f);val temp=values["Temperature"]!!*35f;val tint=values["Tint"]!!*25f;val fade=values["Fade"]!!*30f
        val cm=ColorMatrix();cm.setSaturation((sat*vibrance).coerceIn(0f,2f))
        cm.postConcat(ColorMatrix(floatArrayOf(contrast,0f,0f,0f,exp+fade,0f,contrast,0f,0f,exp+fade,0f,0f,contrast,0f,exp+fade,0f,0f,0f,1f,0f)))
        cm.postConcat(ColorMatrix(floatArrayOf(1f+temp/100f,0f,0f,0f,0f,0f,1f+tint/100f,0f,0f,0f,0f,0f,1f-temp/100f,0f,0f,0f,0f,0f,1f,0f)));return cm
    }
    private fun renderBitmap():Bitmap?{val b=source?:return null;val adjusted=Bitmap.createBitmap(b.width,b.height,Bitmap.Config.ARGB_8888);Canvas(adjusted).drawBitmap(b,0f,0f,Paint().apply{colorFilter=ColorMatrixColorFilter(matrix())});if(rotation%360f==0f)return adjusted;val m=Matrix().apply{postRotate(rotation)};return Bitmap.createBitmap(adjusted,0,0,adjusted.width,adjusted.height,m,true)}
    private fun updatePreview(){renderBitmap()?.let{image.setImageBitmap(it)}}
    private fun reset(){values.keys.forEach{values[it]=0f};rotation=0f;refreshPanel();updatePreview()}
    private fun save(){val out=renderBitmap()?:run{Toast.makeText(this,"Select a photo first",Toast.LENGTH_SHORT).show();return};try{val dir=File(getExternalFilesDir(null),"Photos").apply{mkdirs()};val file=File(dir,"GatsMod-\u0024{System.currentTimeMillis()}.jpg");FileOutputStream(file).use{out.compress(Bitmap.CompressFormat.JPEG,95,it)};val uri=androidx.core.content.FileProvider.getUriForFile(this,packageName+".updates",file);val share=Intent(Intent.ACTION_SEND).apply{type="image/jpeg";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)};startActivity(Intent.createChooser(share,"Share edited photo"));Toast.makeText(this,"Edited image ready to share",Toast.LENGTH_SHORT).show()}catch(e:Exception){Toast.makeText(this,"Export failed: \u0024{e.message}",Toast.LENGTH_LONG).show()}}
}
