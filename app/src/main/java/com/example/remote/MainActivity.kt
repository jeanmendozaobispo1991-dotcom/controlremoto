package com.example.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private lateinit var xiaomi: XiaomiController
    private lateinit var bt: BluetoothController

    private val btPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { bt.start() }

    private fun activateBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) {
            btPermissions.launch(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            )
        } else {
            bt.start()
        }
    }

    private fun makeDiscoverable() {
        startActivity(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ir = SamsungIr(this)
        xiaomi = XiaomiController(this)
        bt = BluetoothController(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var tab by remember { mutableStateOf(0) }
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Samsung") })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Xiaomi WiFi") })
                            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Xiaomi BT") })
                        }
                        when (tab) {
                            0 -> SamsungScreen(ir)
                            1 -> XiaomiScreen(xiaomi)
                            else -> BluetoothScreen(bt, ::activateBluetooth, ::makeDiscoverable)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        xiaomi.shutdown()
        bt.shutdown()
        super.onDestroy()
    }
}

@Composable
fun RowScope.RemoteKey(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label) }
}

@Composable
fun RowScope.Blank() {
    Spacer(Modifier.weight(1f))
}

private val rowModifier = Modifier.fillMaxWidth()
private val gap = Arrangement.spacedBy(10.dp)

@Composable
fun SamsungScreen(ir: SamsungIr) {
    val haptic = LocalHapticFeedback.current

    fun press(code: Long) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        ir.send(code)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Samsung TV (infrarrojo)", style = MaterialTheme.typography.titleLarge)

        if (!ir.available) {
            Text("Este celular no tiene emisor IR.", color = MaterialTheme.colorScheme.error)
        }

        Row(rowModifier, horizontalArrangement = gap) {
            RemoteKey("Power") { press(SamsungIr.POWER) }
            RemoteKey("Entrada") { press(SamsungIr.SOURCE) }
        }
        Row(rowModifier, horizontalArrangement = gap) {
            RemoteKey("Vol −") { press(SamsungIr.VOL_DOWN) }
            RemoteKey("Mute") { press(SamsungIr.MUTE) }
            RemoteKey("Vol +") { press(SamsungIr.VOL_UP) }
        }
        Row(rowModifier, horizontalArrangement = gap) {
            RemoteKey("Canal −") { press(SamsungIr.CH_DOWN) }
            RemoteKey("Canal +") { press(SamsungIr.CH_UP) }
        }
        Row(rowModifier, horizontalArrangement = gap) {
            Blank(); RemoteKey("▲") { press(SamsungIr.UP) }; Blank()
        }
        Row(rowModifier, horizontalArrangement = gap) {
            RemoteKey("◀") { press(SamsungIr.LEFT) }
            RemoteKey("OK") { press(SamsungIr.OK) }
            RemoteKey("▶") { press(SamsungIr.RIGHT) }
        }
        Row(rowModifier, horizontalArrangement = gap) {
            Blank(); RemoteKey("▼") { press(SamsungIr.DOWN) }; Blank()
        }
        Row(rowModifier, horizontalArrangement = gap) {
            RemoteKey("Atrás") { press(SamsungIr.BACK) }
            RemoteKey("Inicio") { press(SamsungIr.HOME) }
            RemoteKey("Menú") { press(SamsungIr.MENU) }
        }

        val n = SamsungIr.NUMBERS
        Row(rowModifier, horizontalArrangement = gap) {
            RemoteKey("1") { press(n[1]) }; RemoteKey("2") { press(n[2]) }; RemoteKey("3") { press(n[3]) }
        }
        Row(rowModifier, horizontalArrangement = gap) {
            RemoteKey("4") { press(n[4]) }; RemoteKey("5") { press(n[5]) }; RemoteKey("6") { press(n[6]) }
        }
        Row(rowModifier, horizontalArrangement = gap) {
            RemoteKey("7") { press(n[7]) }; RemoteKey("8") { press(n[8]) }; RemoteKey("9") { press(n[9]) }
        }
        Row(rowModifier, horizontalArrangement = gap) {
            Blank(); RemoteKey("0") { press(n[0]) }; Blank()
        }
    }
}

@Composable
fun XiaomiScreen(tv: XiaomiController) {
    val haptic = LocalHapticFeedback.current
    var pin by remember { mutableStateOf("") }

    fun press(code: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        tv.key(code)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Xiaomi TV Box (WiFi)", style = MaterialTheme.typography.titleLarge)
        Text(tv.status)

        if (!tv.connected) {
            Button(onClick = { tv.search() }, modifier = rowModifier) {
                Text(if (tv.searching) "Buscando…" else "Buscar TV box en la red")
            }
            tv.found.forEach { (name, ip) ->
                OutlinedButton(onClick = { tv.host = ip }, modifier = rowModifier) { Text("$name  ($ip)") }
            }
            OutlinedTextField(
                value = tv.host,
                onValueChange = { tv.host = it },
                label = { Text("IP del TV box") },
                singleLine = true,
                modifier = rowModifier
            )
            Button(onClick = { tv.connect() }, enabled = !tv.busy, modifier = rowModifier) {
                Text("Conectar")
            }
            if (tv.needPin) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.uppercase().take(6) },
                    label = { Text("Código de la TV (6 caracteres)") },
                    singleLine = true,
                    modifier = rowModifier
                )
                Button(onClick = { tv.submitPin(pin) }, modifier = rowModifier) { Text("Enviar código") }
            }
        } else {
            Row(rowModifier, horizontalArrangement = gap) {
                Blank(); RemoteKey("▲") { press(TvKey.UP) }; Blank()
            }
            Row(rowModifier, horizontalArrangement = gap) {
                RemoteKey("◀") { press(TvKey.LEFT) }
                RemoteKey("OK") { press(TvKey.OK) }
                RemoteKey("▶") { press(TvKey.RIGHT) }
            }
            Row(rowModifier, horizontalArrangement = gap) {
                Blank(); RemoteKey("▼") { press(TvKey.DOWN) }; Blank()
            }
            Row(rowModifier, horizontalArrangement = gap) {
                RemoteKey("Atrás") { press(TvKey.BACK) }
                RemoteKey("Inicio") { press(TvKey.HOME) }
                RemoteKey("Menú") { press(TvKey.MENU) }
            }
            Row(rowModifier, horizontalArrangement = gap) {
                RemoteKey("Vol −") { press(TvKey.VOL_DOWN) }
                RemoteKey("Mute") { press(TvKey.MUTE) }
                RemoteKey("Vol +") { press(TvKey.VOL_UP) }
            }
            Row(rowModifier, horizontalArrangement = gap) {
                RemoteKey("Play/Pausa") { press(TvKey.PLAY_PAUSE) }
                RemoteKey("Power") { press(TvKey.POWER) }
            }
            Row(rowModifier, horizontalArrangement = gap) {
                RemoteKey("YouTube") { tv.openLink("https://www.youtube.com") }
                RemoteKey("Netflix") { tv.openLink("https://www.netflix.com") }
            }
            OutlinedButton(onClick = { tv.disconnect() }, modifier = rowModifier) { Text("Desconectar") }
        }
    }
}

@Composable
fun BluetoothScreen(bt: BluetoothController, onActivate: () -> Unit, onDiscoverable: () -> Unit) {
    val haptic = LocalHapticFeedback.current

    fun keyboard(usage: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        bt.keyboard(usage)
    }

    fun consumer(usage: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        bt.consumer(usage)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Xiaomi TV Box (Bluetooth)", style = MaterialTheme.typography.titleLarge)
        Text(bt.status)

        if (!bt.connected) {
            Button(onClick = onActivate, modifier = rowModifier) { Text("1. Activar") }
            Button(onClick = onDiscoverable, modifier = rowModifier) { Text("2. Hacer visible el celular") }
            Text(
                "3. En la TV: Ajustes > Control remoto y accesorios > Agregar accesorio, y elige \"Control Remoto\".",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = { bt.refreshPaired() }, modifier = rowModifier) { Text("Actualizar lista") }
            bt.paired.forEach { (name, address) ->
                OutlinedButton(onClick = { bt.connect(address) }, modifier = rowModifier) { Text(name) }
            }
        } else {
            Row(rowModifier, horizontalArrangement = gap) {
                Blank(); RemoteKey("▲") { keyboard(HidKey.UP) }; Blank()
            }
            Row(rowModifier, horizontalArrangement = gap) {
                RemoteKey("◀") { keyboard(HidKey.LEFT) }
                RemoteKey("OK") { keyboard(HidKey.ENTER) }
                RemoteKey("▶") { keyboard(HidKey.RIGHT) }
            }
            Row(rowModifier, horizontalArrangement = gap) {
                Blank(); RemoteKey("▼") { keyboard(HidKey.DOWN) }; Blank()
            }
            Row(rowModifier, horizontalArrangement = gap) {
                RemoteKey("Atrás") { consumer(HidKey.C_BACK) }
                RemoteKey("Inicio") { consumer(HidKey.C_HOME) }
                RemoteKey("Menú") { consumer(HidKey.C_MENU) }
            }
            Row(rowModifier, horizontalArrangement = gap) {
                RemoteKey("Vol −") { consumer(HidKey.C_VOL_DOWN) }
                RemoteKey("Mute") { consumer(HidKey.C_MUTE) }
                RemoteKey("Vol +") { consumer(HidKey.C_VOL_UP) }
            }
            Row(rowModifier, horizontalArrangement = gap) {
                RemoteKey("Play/Pausa") { consumer(HidKey.C_PLAY_PAUSE) }
                RemoteKey("Power") { consumer(HidKey.C_POWER) }
            }
        }
    }
}
