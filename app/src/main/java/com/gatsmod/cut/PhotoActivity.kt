package com.gatsmod.cut

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
    private var category = "Light"
    private val values = linkedMapOf("Exposure" to 0f, "Contrast" to 0f, "Temperature" to 0f, "Saturation" to 0f)
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { load(it) } }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun text(s:String,size:Float=14f,color:Int=Color.WHITE):TextView=TextView(this).apply { text=s; textSize=size; setTextColor(color); gravity=Gravity.CENTER; setPadding(dp(8),dp(8),dp(8),dp(8)) }
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        window.statusBarColor=Color.BLACK; window.navigationBarColor=Color.BLACK
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xFF101114.toInt())}
        val top=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        top.addView(text("‹",26f).apply{setOnClickListener{finish()}},LinearLayout.LayoutParams(dp(52),dp(48)))
        top.addView(text("GatsMod Photo",17f),LinearLayout.LayoutParams(0,dp(48),1f))
        top.addView(text("Reset",12f).apply{setOnClickListener{reset()}},LinearLayout.LayoutParams(dp(56),dp(48)))
        top.addView(text("Export",15f).apply{setOnClickListener{save()}},LinearLayout.LayoutParams(dp(64),dp(48)))
        root.addView(top)
        image=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK)}
        root.addView(image,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        val categories=LinearLayout(this).apply{gravity=Gravity.CENTER}
        listOf("Light","Color","Effects","Detail","Crop").forEach{ name->categories.addView(text(name,13f).apply{setOnClickListener{category=name;refreshPanel()}})}
        root.addView(categories)
        panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(panel,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(190)))
        root.addView(text("Choose Photo",14f,0xFF80D8F2.toInt()).apply{setOnClickListener{picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}})
        setContentView(root)
        refreshPanel()
        picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    private fun load(uri:Uri){try{
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
        var sample=1; while(bounds.outWidth/sample>2048 || bounds.outHeight/sample>2048) sample*=2
        source=contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply{inSampleSize=sample})}
        reset()
    }catch(e:Exception){Toast.makeText(this,"Could not open image",Toast.LENGTH_LONG).show()}}
    private fun refreshPanel(){
        panel.removeAllViews()
        val keys=when(category){
            "Light"->listOf("Exposure","Contrast")
            "Color"->listOf("Temperature","Saturation")
            else->emptyList()
        }
        if(keys.isEmpty()){panel.addView(text("$category tools are planned for the next editing stages.",12f,0xFF9CA3AF.toInt()));return}
        keys.forEach{k->
            val line=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
            line.addView(text(k,12f),LinearLayout.LayoutParams(dp(92),dp(44)))
            val seek=SeekBar(this).apply{max=200;progress=(values[k] * 100 +100).toInt().coerceIn(0,200)}
            seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(s:SeekBar?,p:Int,from:Boolean){if(from){values[k]=(p-100)/100f;updatePreview()}}
                override fun onStartTrackingTouch(s:SeekBar?){}
                override fun onStopTrackingTouch(s:SeekBar?){}
            })
            line.addView(seek,LinearLayout.LayoutParams(0,dp(44),1f))
            panel.addView(line)
        }
    }
    private fun matrix():ColorMatrix{
        val exp=values["Exposure"]!!; val contrast=1f+values["Contrast"]!!; val sat=(1f+values["Saturation"]!!).coerceIn(0f,2f); val temp=values["Temperature"]!!
        val cm=ColorMatrix()
        cm.setSaturation(sat)
        cm.postConcat(ColorMatrix(floatArrayOf(contrast,0f,0f,0f,exp*100f,0f,contrast,0f,0f,exp*100f,0f,0f,contrast,0f,exp*100f,0f,0f,0f,1f,0f)))
        cm.postConcat(ColorMatrix(floatArrayOf(1f+temp*0.15f,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,1f-temp*0.15f,0f,0f,0f,0f,0f,1f,0f)))
        return cm
    }
    private fun updatePreview(){source?.let{image.setImageBitmap(it);image.colorFilter=android.graphics.ColorMatrixColorFilter(matrix())}}
    private fun reset(){values.keys.forEach{values[it]=0f};refreshPanel();updatePreview()}
    private fun save(){
        val b=source?:run{Toast.makeText(this,"Select a photo first",Toast.LENGTH_SHORT).show();return}
        try{
            val out=Bitmap.createBitmap(b.width,b.height,Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(b,0f,0f,Paint().apply{colorFilter=ColorMatrixColorFilter(matrix())})
            val dir=File(getExternalFilesDir(null),"Photos").apply{mkdirs()}
            val file=File(dir,"GatsMod-${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use{out.compress(Bitmap.CompressFormat.JPEG,95,it)}
            val uri=androidx.core.content.FileProvider.getUriForFile(this,packageName+".updates",file)
            val share=Intent(Intent.ACTION_SEND).apply{type="image/jpeg";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            startActivity(Intent.createChooser(share,"Share edited photo"))
            Toast.makeText(this,"Edited image ready to share",Toast.LENGTH_SHORT).show()
        }catch(e:Exception){Toast.makeText(this,"Export failed: ${e.message}",Toast.LENGTH_LONG).show()}
    }
}