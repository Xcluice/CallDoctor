package com.xcluice.calldoctor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.CellSignalStrengthLte
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Snap(
    val rsrp: Int? = null,
    val sinr: Int? = null,
    val level: Int? = null,
    val dataType: String = "-",
    val voiceType: String = "-",
    val operator: String = "-",
    val error: String? = null
)

fun netName(t: Int): String = when (t) {
    TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
    19 -> "4G LTE-CA"
    20 -> "5G NR"
    TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
    TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
    TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
    TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
    TelephonyManager.NETWORK_TYPE_UNKNOWN -> "None"
    else -> "Other ($t)"
}

fun hasPerms(c: Context) =
    c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
fun readSnap(c: Context): Snap = try {
    val tm = c.getSystemService(TelephonyManager::class.java)
    val ss = tm.signalStrength
    val lte = ss?.cellSignalStrengths?.filterIsInstance<CellSignalStrengthLte>()?.firstOrNull()
    Snap(
        rsrp = lte?.rsrp?.takeIf { it != Int.MAX_VALUE && it < 0 },
        sinr = lte?.rssnr?.takeIf { it != Int.MAX_VALUE },
        level = ss?.level,
        dataType = netName(tm.dataNetworkType),
        voiceType = netName(tm.voiceNetworkType),
        operator = tm.networkOperatorName.ifBlank { "-" }
    )
} catch (e: Exception) {
    Snap(error = e.message)
}

fun rsrpLabel(r: Int?): Pair<String, Color> = when {
    r == null -> "No LTE reading" to Color.Gray
    r >= -95 -> "Good" to Color(0xFF4CAF50)
    r >= -105 -> "Fair" to Color(0xFFFFC107)
    r >= -115 -> "Poor (calls may break up)" to Color(0xFFFF9800)
    else -> "Very weak (calls will break up)" to Color(0xFFF44336)
}

fun sinrLabel(s: Int?): String = when {
    s == null -> "n/a"
    s >= 10 -> "clean"
    s >= 0 -> "some interference"
    else -> "noisy (bad for voice)"
}

fun loadLog(c: Context): List<String> =
    c.getSharedPreferences("log", 0).getString("l", "")!!.split("\n").filter { it.isNotBlank() }

fun saveLog(c: Context, l: List<String>) {
    c.getSharedPreferences("log", 0).edit().putString("l", l.take(60).joinToString("\n")).apply()
}

fun open(c: Context, i: Intent) {
    try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf(Snap()) }
    var log by remember { mutableStateOf(loadLog(ctx)) }
    var granted by remember { mutableStateOf(hasPerms(ctx)) }
    var total by remember { mutableStateOf(0) }
    var weak by remember { mutableStateOf(0) }
    var worst by remember { mutableStateOf<Int?>(null) }
    var cam by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerms(ctx)
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    LaunchedEffect(granted) {
        while (true) {
            snap = readSnap(ctx)
            snap.rsrp?.let { r ->
                total++
                if (r < -110) weak++
                worst = if (worst == null) r else minOf(worst!!, r)
            }
            delay(2000)
        }
    }

    val (label, col) = rsrpLabel(snap.rsrp)
    val voiceOk = snap.voiceType.contains("LTE") || snap.voiceType.contains("5G")

    if (cam) { CameraScreen(snap) { cam = false }; return }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Call Doctor", fontSize = 26.sp)
        if (!granted) {
            Button(onClick = { launcher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)) }) {
                Text("Grant permission to read signal")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Live signal", fontSize = 16.sp)
                Text(label, color = col, fontSize = 20.sp)
                Text("RSRP: ${snap.rsrp ?: "-"} dBm   (better than -95)")
                Text("SINR: ${snap.sinr ?: "-"} dB   ${sinrLabel(snap.sinr)}")
                Text("Bars: ${snap.level ?: "-"} / 4")
                Text("Data network: ${snap.dataType}")
                Text("Voice network: ${snap.voiceType}")
                Text("Operator: ${snap.operator}")
                if (granted && snap.voiceType != "-" && !voiceOk)
                    Text("Voice is NOT on LTE right now. On Jio that means calls will fail or break up.", color = Color(0xFFF44336))
                snap.error?.let { Text("Error: $it", color = Color(0xFFF44336)) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Since app opened", fontSize = 16.sp)
                Text("Weak samples (below -110 dBm): $weak of $total")
                Text("Worst RSRP: ${worst ?: "-"} dBm")
                Button(onClick = { total = 0; weak = 0; worst = null }) { Text("Reset stats") }
            }
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            val t = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
            val e = "$t | RSRP ${snap.rsrp ?: "-"} | SINR ${snap.sinr ?: "-"} | data ${snap.dataType} | voice ${snap.voiceType}"
            log = listOf(e) + log
            saveLog(ctx, log)
        }) { Text("Call was bad (log it now)") }

        Button(modifier = Modifier.fillMaxWidth(), onClick = { cam = true }) { Text("Signal camera (paint the signal map)") }
        Text("Shortcuts", fontSize = 16.sp)
        Button(modifier = Modifier.fillMaxWidth(), onClick = { open(ctx, Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)) }) { Text("Mobile network settings") }
        Button(modifier = Modifier.fillMaxWidth(), onClick = { open(ctx, Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) }) { Text("Preferred network type / SIM") }
        Button(modifier = Modifier.fillMaxWidth(), onClick = { open(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS)) }) { Text("Wireless and airplane mode") }
        Button(modifier = Modifier.fillMaxWidth(), onClick = { open(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode("*#*#4636#*#*")))) }) { Text("Hidden phone info (may not work on Oppo)") }
        Button(modifier = Modifier.fillMaxWidth(), onClick = { open(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:199"))) }) { Text("Call Jio care (199)") }
        Button(modifier = Modifier.fillMaxWidth(), onClick = { open(ctx, Intent(Settings.ACTION_SETTINGS)) }) { Text("Open Settings (reset network is under Additional Settings)") }

        Text("Bad-call log", fontSize = 16.sp)
        if (log.isEmpty()) Text("Nothing logged yet. Tap the button right after a bad call.")
        log.forEach { Text(it, fontSize = 12.sp) }
        if (log.isNotEmpty()) OutlinedButton(onClick = { log = emptyList(); saveLog(ctx, log) }) { Text("Clear log") }
    }
}
