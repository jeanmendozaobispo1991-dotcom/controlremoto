package com.example.remote

import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** Certificado propio de la app, con el que el TV box reconoce a este celular. */
interface TvIdentity {
    val privateKey: PrivateKey
    val certificate: X509Certificate
}

/** Códigos de tecla de Android (KeyEvent). */
object TvKey {
    const val HOME = 3
    const val BACK = 4
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val VOL_UP = 24
    const val VOL_DOWN = 25
    const val POWER = 26
    const val OK = 23
    const val MENU = 82
    const val PLAY_PAUSE = 85
    const val MUTE = 164
    const val CH_UP = 166
    const val CH_DOWN = 167
}

private class FixedKeyManager(
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
    override fun getPrivateKey(alias: String?): PrivateKey = key

    companion object { const val ALIAS = "client" }
}

/** El TV box usa un certificado propio (no firmado por nadie), así que aquí se acepta tal cual. */
@Suppress("TrustAllX509TrustManager", "CustomX509TrustManager")
private object TrustAll : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

object TvTls {
    fun open(identity: TvIdentity, host: String, port: Int): SSLSocket {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(
            arrayOf<KeyManager>(FixedKeyManager(identity.privateKey, arrayOf(identity.certificate))),
            arrayOf<TrustManager>(TrustAll),
            null
        )
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.startHandshake()
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        return socket
    }
}

/** Emparejamiento (puerto 6467): el TV muestra un código de 6 caracteres que hay que escribir en la app. */
class TvPairing(private val identity: TvIdentity) {
    private val pinQueue = LinkedBlockingQueue<String>()
    @Volatile private var socket: SSLSocket? = null

    fun submitPin(pin: String) { pinQueue.offer(pin) }
    fun cancel() { try { socket?.close() } catch (_: Exception) {} }

    /** Bloqueante: llamar desde un hilo. Lanza excepción si algo falla. */
    fun run(host: String, onPinRequested: () -> Unit) {
        val s = TvTls.open(identity, host, 6467)
        socket = s
        try {
            val input = s.inputStream
            val output = s.outputStream
            val serverCert = s.session.peerCertificates[0] as X509Certificate

            fun send(w: ProtoWriter) { output.write(w.framed()); output.flush() }
            fun base() = ProtoWriter().varint(1, 2).varint(2, 200)
            fun encoding() = ProtoWriter().varint(1, 3).varint(2, 6) // hexadecimal, 6 símbolos

            send(base().message(10, ProtoWriter().string(1, "com.example.remote").string(2, "Control Remoto")))

            while (true) {
                val m = ProtoMessage.readFrame(input)
                val status = m.long(2) ?: 200L
                if (status != 200L) throw IOException("El TV rechazó el emparejamiento (código $status). ¿El PIN era correcto?")
                when {
                    m.has(11) -> send(base().message(20, ProtoWriter().message(1, encoding()).varint(3, 1)))
                    m.has(20) -> send(base().message(30, ProtoWriter().message(1, encoding()).varint(2, 1)))
                    m.has(31) -> {
                        onPinRequested()
                        val pin = pinQueue.poll(120, TimeUnit.SECONDS) ?: throw IOException("Se agotó el tiempo para escribir el código")
                        send(base().message(40, ProtoWriter().bytes(1, secretFor(pin, serverCert))))
                    }
                    m.has(41) -> return
                }
            }
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun secretFor(pin: String, serverCert: X509Certificate): ByteArray {
        val code = pin.trim().uppercase()
        require(code.length == 6 && code.all { it in "0123456789ABCDEF" }) {
            "El código debe tener 6 caracteres (0-9 y A-F)"
        }
        val codeBytes = ByteArray(3) { code.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val client = identity.certificate.publicKey as RSAPublicKey
        val server = serverCert.publicKey as RSAPublicKey
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsigned(client.modulus))
        digest.update(unsigned(client.publicExponent))
        digest.update(unsigned(server.modulus))
        digest.update(unsigned(server.publicExponent))
        digest.update(codeBytes, 1, 2)
        return digest.digest()
    }

    private fun unsigned(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }
}

/** Conexión de control (puerto 6466), ya emparejado. */
class TvConnection(private val identity: TvIdentity) {
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closedByUser = false
    private val sender = Executors.newSingleThreadExecutor()
    private val lock = Any()

    fun start(host: String, onReady: () -> Unit, onClosed: (error: String?, wasReady: Boolean) -> Unit) {
        Thread {
            var error: String? = null
            var ready = false
            try {
                val s = TvTls.open(identity, host, 6466)
                socket = s
                val input = s.inputStream
                while (true) {
                    val m = ProtoMessage.readFrame(input)
                    when {
                        m.has(1) -> {
                            val device = ProtoWriter()
                                .string(1, "Redmi").string(2, "Xiaomi")
                                .varint(3, 1).string(4, "1")
                                .string(5, "androidtv-remote").string(6, "1.0.0")
                            writeNow(ProtoWriter().message(1, ProtoWriter().varint(1, 622).message(2, device)))
                        }
                        m.has(2) -> writeNow(ProtoWriter().message(2, ProtoWriter().varint(1, 622)))
                        m.has(8) -> {
                            val v = m.message(8)?.long(1) ?: 0L
                            writeNow(ProtoWriter().message(9, ProtoWriter().varint(1, v)))
                        }
                        m.has(40) -> { ready = true; onReady() }
                    }
                }
            } catch (e: Exception) {
                if (!closedByUser) error = e.message ?: e.javaClass.simpleName
            } finally {
                close()
                onClosed(error, ready)
            }
        }.start()
    }

    private fun writeNow(w: ProtoWriter) {
        synchronized(lock) {
            val out = socket?.outputStream ?: return
            out.write(w.framed())
            out.flush()
        }
    }

    private fun send(w: ProtoWriter) {
        try { sender.execute { try { writeNow(w) } catch (_: Exception) {} } } catch (_: Exception) {}
    }

    /** direction 3 = pulsación corta */
    fun key(code: Int) = send(ProtoWriter().message(10, ProtoWriter().varint(1, code).varint(2, 3)))

    fun openLink(url: String) = send(ProtoWriter().message(90, ProtoWriter().string(1, url)))

    fun close() {
        closedByUser = true
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        sender.shutdown()
    }
}
