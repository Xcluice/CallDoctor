package com.xcluice.calldoctor

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor

fun colorFor(r: Int): Color = when {
    r >= -90 -> Color(0xFFFF1E1E)
    r >= -100 -> Color(0xFFFF8A00)
    r >= -110 -> Color(0xFFFFD600)
    else -> Color(0xFF2979FF)
}

@Composable
fun CameraScreen(snap: Snap, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as ComponentActivity
    var camOk by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    LaunchedEffect(Unit) { if (!camOk) launcher.launch(Manifest.permission.CAMERA) }

    val cells = remember { mutableStateMapOf<Int, Int>() }
    var az by remember { mutableStateOf(0f) }
    var el by remember { mutableStateOf(0f) }
    val cur by rememberUpdatedState(snap)

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rm = FloatArray(9)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rm, e.values)
                val fx = -rm[2]; val fy = -rm[5]; val fz = -rm[8]
                var a = Math.toDegrees(atan2(fx, fy).toDouble()).toFloat()
                if (a < 0) a += 360f
                val ev = Math.toDegrees(asin(fz.coerceIn(-1f, 1f).toDouble())).toFloat()
                az = a; el = ev
                val r = cur.rsrp
                if (r != null) {
                    val ab = (a / 10f).toInt() % 36
                    val eb = floor(ev / 10f).toInt().coerceIn(-9, 9)
                    for (da in -1..1) for (de in -1..1) {
                        val ee = eb + de
                        if (ee !in -9..9) continue
                        val key = ((ab + da + 36) % 36) * 100 + ee + 20
                        if ((da == 0 && de == 0) || !cells.containsKey(key)) cells[key] = r
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        if (camOk) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                val pv = PreviewView(c)
                val f = ProcessCameraProvider.getInstance(c)
                f.addListener({
                    try {
                        val p = f.get()
                        val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        p.unbindAll()
                        p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, prev)
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(c))
                pv
            })
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val hf = 55f; val vf = 70f
            for ((k, r) in cells) {
                val ca = (k / 100) * 10f + 5f
                val ce = ((k % 100) - 20) * 10f + 5f
                var da = ca - az
                while (da > 180) da -= 360
                while (da < -180) da += 360
                val de = ce - el
                if (abs(da) > hf / 2 + 10 || abs(de) > vf / 2 + 10) continue
                val x = w / 2 + da / hf * w
                val y = h / 2 - de / vf * h
                val cw = 10f / hf * w; val ch = 10f / vf * h
                drawRect(colorFor(r).copy(alpha = 0.45f), Offset(x - cw / 2, y - ch / 2), Size(cw, ch))
            }
            drawCircle(Color.White, 10f, Offset(w / 2, h / 2), style = Stroke(3f))
        }
        val best = cells.maxByOrNull { it.value }
        val hint = if (best == null) "Sweep the phone slowly around you" else {
            var d = (best.key / 100) * 10f + 5f - az
            while (d > 180) d -= 360
            while (d < -180) d += 360
            val dir = when {
                abs(d) < 12 -> "straight ahead"
                d > 0 -> "turn right ${d.toInt()} deg"
                else -> "turn left ${(-d).toInt()} deg"
            }
            "Strongest: ${best.value} dBm, $dir"
        }
        val (label, col) = rsrpLabel(snap.rsrp)
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xAA000000)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Now: ${snap.rsrp ?: "-"} dBm  $label", color = col, fontSize = 16.sp)
            Text(hint, color = Color.White, fontSize = 14.sp)
            Text("Red strong | Orange good | Yellow weak | Blue very weak", color = Color.White, fontSize = 11.sp)
            Text("Stand still, sweep slowly. Then walk elsewhere and compare.", color = Color.LightGray, fontSize = 11.sp)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xAA000000)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onBack) { Text("Back") }
            OutlinedButton(onClick = { cells.clear() }) { Text("Clear map") }
        }
    }
}
