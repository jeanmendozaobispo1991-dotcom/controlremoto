package com.example.remote

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** Escritor mínimo de protobuf (solo lo que usa el protocolo del control remoto). */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun tag(field: Int, wireType: Int) = rawVarint(((field shl 3) or wireType).toLong())

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, 0); rawVarint(value); return this
    }

    fun varint(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bytes(field: Int, data: ByteArray): ProtoWriter {
        tag(field, 2); rawVarint(data.size.toLong()); out.write(data); return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, inner: ProtoWriter): ProtoWriter = bytes(field, inner.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    /** Mensaje con prefijo de longitud (así viajan por el socket). */
    fun framed(): ByteArray {
        val body = toByteArray()
        val w = ProtoWriter()
        w.rawVarint(body.size.toLong())
        w.out.write(body)
        return w.toByteArray()
    }
}

/** Lector mínimo de protobuf. */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int): Boolean = fields.containsKey(field)
    fun long(field: Int): Long? = fields[field]?.firstOrNull() as? Long
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val map = HashMap<Int, MutableList<Any>>()
            var pos = 0

            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                }
                return result
            }

            while (pos < data.size) {
                val key = readVarint().toInt()
                val field = key ushr 3
                when (key and 7) {
                    0 -> map.getOrPut(field) { ArrayList() }.add(readVarint())
                    2 -> {
                        val len = readVarint().toInt()
                        map.getOrPut(field) { ArrayList() }.add(data.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    1 -> pos += 8
                    5 -> pos += 4
                    else -> break
                }
            }
            return ProtoMessage(map)
        }

        /** Lee un mensaje con prefijo de longitud desde el socket. */
        fun readFrame(input: InputStream): ProtoMessage {
            var len = 0
            var shift = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("La conexión se cerró")
                len = len or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) throw EOFException("La conexión se cerró")
                off += n
            }
            return parse(buf)
        }
    }
}
