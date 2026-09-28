package com.example.amped3controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var controller:AmpedMidiController
    private var notice by mutableStateOf("")
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){ uri ->
        if(uri!=null) lifecycleScope.launch {
            notice = runCatching { withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(controller.exportData()) }
            }; T("Libreria e backup esportati", "Library and backups exported") }
                .getOrElse { "Export: ${it.message}" }
        }
    }
    private val import=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
        if(uri!=null) lifecycleScope.launch {
            notice=runCatching { withContext(Dispatchers.IO) {
                val data = contentResolver.openInputStream(uri)!!.use { Recovery.readLimited(it, 32_000_000) }
                controller.importData(data.toString(Charsets.UTF_8))
            }}.getOrElse { "Import: ${it.message}" }
        }
    }
    private val importIr=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
        if(uri!=null) lifecycleScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { Recovery.readLimited(it, 16*1024*1024) }
                }
                val name=uri.lastPathSegment?.substringAfterLast('/')?.take(80) ?: "impulse.wav"
                controller.convertIr(bytes,name)
            }.onFailure { notice=it.message ?: "Import failed" }
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        controller=ViewModelProvider(this)[ControllerModel::class.java].controller
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Red,onPrimary=Coal,background=Coal,surface=Coal,surfaceVariant=Panel,onSurface=Paper,onSurfaceVariant=Muted,secondary=Red)) {
                AmpedApp(controller,notice,{export.launch("Amped3-preset-e-backup.json")},{import.launch(arrayOf("application/json","text/plain","text/xml","application/xml","*/*"))},{importIr.launch(arrayOf("audio/wav","audio/x-wav","application/octet-stream","*/*"))})
            }
        }
        val debug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (savedInstanceState == null) {
            if (debug && intent?.getBooleanExtra("demo", false) == true) controller.enableDemo() else controller.connectToAmp()
        }
        // debug-only hook so the conversion path can be exercised without driving the file picker:
        //   adb shell am start ... --ez demo true --es ir /sdcard/Download/some.wav
        if (debug && savedInstanceState == null) intent?.getStringExtra("ir")?.let { path ->
            runCatching { java.io.File(path).inputStream().use { Recovery.readLimited(it, 16*1024*1024) } }
                .onSuccess { controller.convertIr(it, java.io.File(path).name) }
                .onFailure { notice = T("IR non leggibile: ${it.message}", "Cannot read that IR: ${it.message}") }
        }
    }

}

var lang by mutableStateOf("en")
fun T(it: String, en: String) = if (lang == "it") it else en
