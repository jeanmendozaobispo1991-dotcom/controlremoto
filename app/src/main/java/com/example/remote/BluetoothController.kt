package com.example.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/** Teclas HID: las del teclado (página 7) y las de control multimedia (página 12). */
object HidKey {
    const val ENTER = 0x28
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52

    const val C_POWER = 0x0030
    const val C_MENU = 0x0040
    const val C_PLAY_PAUSE = 0x00CD
    const val C_MUTE = 0x00E2
    const val C_VOL_UP = 0x00E9
    const val C_VOL_DOWN = 0x00EA
    const val C_HOME = 0x0223
    const val C_BACK = 0x0224
}

private val HID_DESCRIPTOR: ByteArray = intArrayOf(
    // Teclado (report 1)
    0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
    0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
    0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
    0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
    0xC0,
    // Control multimedia (report 2)
    0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
    0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
    0xC0
).let { a -> ByteArray(a.size) { a[it].toByte() } }

/** Hace que el celular se comporte como un control Bluetooth (teclado + teclas multimedia). */
@SuppressLint("MissingPermission")
class BluetoothController(context: Context) {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null

    var status by mutableStateOf("Toca Activar para empezar")
    var ready by mutableStateOf(false)
    var connected by mutableStateOf(false)
    val paired = mutableStateListOf<Pair<String, String>>()

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            ready = registered
            status = if (registered) "Listo. Ahora empareja desde la TV" else "No se pudo registrar el control"
            refreshPaired()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (device == null) return
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device
                connected = true
                status = "Conectado a ${device.name ?: device.address}"
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && host?.address == device.address) {
                host = null
                connected = false
                status = "Desconectado"
            }
        }
    }

    fun start() {
        val a = adapter
        if (a == null) { status = "Este celular no tiene Bluetooth"; return }
        if (!a.isEnabled) { status = "Activa el Bluetooth del celular y vuelve a intentar"; return }
        status = "Preparando…"
        val ok = a.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Control Remoto", "Control remoto por Bluetooth", "Android",
                    BluetoothHidDevice.SUBCLASS1_COMBO, HID_DESCRIPTOR
                )
                val registered = h.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback)
                if (!registered) status = "Tu celular no permite usar el Bluetooth como control"
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null; ready = false; connected = false
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) status = "Tu celular no permite usar el Bluetooth como control"
    }

    fun refreshPaired() {
        try {
            paired.clear()
            adapter?.bondedDevices?.forEach { paired.add((it.name ?: it.address) to it.address) }
        } catch (_: SecurityException) {
        }
    }

    fun connect(address: String) {
        try {
            status = "Conectando…"
            hid?.connect(adapter?.getRemoteDevice(address))
        } catch (e: Exception) {
            status = "No se pudo conectar: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun report(id: Int, data: ByteArray) {
        val d = host ?: return
        try { hid?.sendReport(d, id, data) } catch (_: Exception) {}
    }

    /** Tecla de teclado (flechas, Enter). */
    fun keyboard(usage: Int) {
        report(1, byteArrayOf(0, 0, usage.toByte(), 0, 0, 0, 0, 0))
        main.postDelayed({ report(1, ByteArray(8)) }, 50)
    }

    /** Tecla multimedia (inicio, atrás, volumen…). */
    fun consumer(usage: Int) {
        report(2, byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte()))
        main.postDelayed({ report(2, byteArrayOf(0, 0)) }, 50)
    }

    fun shutdown() {
        try {
            hid?.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (_: Exception) {
        }
    }
}
