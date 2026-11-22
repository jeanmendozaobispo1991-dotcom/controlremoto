package com.example.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** Crea (una sola vez) y guarda el certificado con el que este celular se identifica ante el TV box. */
class TvCredentials(context: Context) : TvIdentity {
    private val prefs = context.getSharedPreferences("tv_credentials", Context.MODE_PRIVATE)
    private val pair: Pair<PrivateKey, X509Certificate> = load() ?: create()

    override val privateKey: PrivateKey get() = pair.first
    override val certificate: X509Certificate get() = pair.second

    private fun load(): Pair<PrivateKey, X509Certificate>? {
        val k = prefs.getString("key", null) ?: return null
        val c = prefs.getString("cert", null) ?: return null
        return try {
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(k, Base64.NO_WRAP)))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.decode(c, Base64.NO_WRAP))) as X509Certificate
            key to cert
        } catch (e: Exception) {
            null
        }
    }

    private fun create(): Pair<PrivateKey, X509Certificate> {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=control-remoto")
        val now = Date()
        val end = Date(now.time + 10L * 365 * 24 * 3600 * 1000)
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now.time), now, end, name, kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        prefs.edit()
            .putString("key", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("cert", Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            .apply()
        return kp.private to cert
    }
}

/** Busca TV boxes Android TV / Google TV en la red WiFi (mDNS). */
class TvDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var busy = false
    private var callback: ((String, String) -> Unit)? = null

    @Synchronized
    private fun next() {
        if (busy) return
        val info = queue.removeFirstOrNull() ?: return
        busy = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@TvDiscovery) { busy = false }
                next()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                if (host != null) callback?.invoke(serviceInfo.serviceName, host)
                synchronized(this@TvDiscovery) { busy = false }
                next()
            }
        })
    }

    fun start(onFound: (name: String, host: String) -> Unit) {
        stop()
        callback = onFound
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(this@TvDiscovery) { queue.addLast(serviceInfo) }
                next()
            }
        }
        listener = l
        try {
            nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {
            listener = null
        }
    }

    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
    }
}

/** Estado y acciones de la pestaña del Xiaomi. */
class XiaomiController(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("xiaomi", Context.MODE_PRIVATE)
    private val identity by lazy { TvCredentials(app) }
    private val discovery = TvDiscovery(app)
    private val main = Handler(Looper.getMainLooper())

    var status by mutableStateOf("Sin conexión")
    var host by mutableStateOf(prefs.getString("host", "") ?: "")
    var connected by mutableStateOf(false)
    var needPin by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var searching by mutableStateOf(false)
    val found = mutableStateListOf<Pair<String, String>>()

    private var connection: TvConnection? = null
    private var pairing: TvPairing? = null

    fun search() {
        found.clear()
        searching = true
        status = "Buscando TV box en tu WiFi…"
        discovery.start { name, ip ->
            if (found.none { it.second == ip }) found.add(name to ip)
            if (host.isBlank()) host = ip
        }
        main.postDelayed({ stopSearch() }, 15000)
    }

    private fun stopSearch() {
        discovery.stop()
        if (searching) {
            searching = false
            if (!connected && !busy) {
                status = if (found.isEmpty()) "No encontré ninguno. Escribe la IP a mano." else "Elige un equipo y toca Conectar"
            }
        }
    }

    fun connect() {
        val h = host.trim()
        if (h.isEmpty() || busy) return
        stopSearch()
        prefs.edit().putString("host", h).apply()
        busy = true
        Thread {
            try {
                val id = identity
                if (!prefs.getBoolean("paired_$h", false)) {
                    status = "Emparejando… mira la pantalla de la TV"
                    val p = TvPairing(id)
                    pairing = p
                    p.run(h) {
                        needPin = true
                        status = "Escribe el código que aparece en la TV"
                    }
                    needPin = false
                    prefs.edit().putBoolean("paired_$h", true).apply()
                }
                status = "Conectando…"
                val c = TvConnection(id)
                connection = c
                c.start(
                    h,
                    onReady = { connected = true; busy = false; status = "Conectado a $h" },
                    onClosed = { error, wasReady ->
                        connected = false
                        busy = false
                        if (!wasReady) prefs.edit().remove("paired_$h").apply() // la próxima vez vuelve a emparejar
                        status = if (error != null) "Desconectado: $error" else "Desconectado"
                    }
                )
            } catch (e: Exception) {
                needPin = false
                busy = false
                status = "Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }.start()
    }

    fun submitPin(pin: String) {
        status = "Verificando código…"
        pairing?.submitPin(pin)
    }

    fun key(code: Int) { connection?.key(code) }

    fun openLink(url: String) { connection?.openLink(url) }

    fun disconnect() {
        pairing?.cancel()
        connection?.close()
        connected = false
        needPin = false
        busy = false
        status = "Sin conexión"
    }

    fun shutdown() {
        discovery.stop()
        pairing?.cancel()
        connection?.close()
    }
}
