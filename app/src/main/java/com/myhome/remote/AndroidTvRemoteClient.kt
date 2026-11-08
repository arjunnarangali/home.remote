package com.myhome.remote

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Arrays
import java.util.Date
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** Minimal Android TV Remote v2 client (pairing on 6467 and commands on 6466). */
internal class AndroidTvRemoteClient(context: Context, private val host: String) {
    private val prefs = context.applicationContext.getSharedPreferences("android_tv_remote", Context.MODE_PRIVATE)
    private val alias = "android-tv-remote-${host.replace(Regex("[^A-Za-z0-9]"), "_")}"
    private val pinKey = "certificate:$host"
    private val lock = Any()
    private val voiceLock = Any()
    private val writes = Executors.newSingleThreadExecutor { task ->
        Thread(task, "android-tv-remote-writer").apply { isDaemon = true }
    }
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: java.io.OutputStream? = null
    @Volatile var connected: Boolean = false
        private set
    @Volatile var lastError: String = ""
        private set
    private var pairingSocket: SSLSocket? = null
    private var loop: Thread? = null
    @Volatile private var stopped = false
    @Volatile private var features = 1 or 2 or 4 or 8 or 32 or 64 or 512
    @Volatile private var imeCounter = 0
    @Volatile private var imeFieldCounter = 0
    @Volatile private var waitingForVoiceSession: CompletableDeferred<Int>? = null
    @Volatile private var activeVoiceSession: Int? = null

    fun isPaired() = prefs.contains(pinKey)
    fun pairingSessionActive() = pairingSocket?.isClosed == false

    @Synchronized
    fun beginPairing() {
        cancelPairing()
        val s = createSocket(host, 6467, 10_000)
        pairingSocket = s
        val hex6 = Proto.Writer().varint(1, 3).varint(2, 6)
        pairingRoundTrip(s, 10, Proto.Writer().string(1, "my.home Remote").string(2, Build.MODEL).bytes(), 11)
        pairingRoundTrip(s, 20, Proto.Writer().message(1, hex6).varint(3, 1).bytes(), 20)
        pairingRoundTrip(s, 30, Proto.Writer().message(1, hex6).varint(2, 1).bytes(), 31)
        s.soTimeout = 15_000
    }

    @Synchronized
    fun finishPairing(codeValue: String) {
        val s = pairingSocket ?: throw IOException("Start pairing first")
        val code = codeValue.filterNot(Char::isWhitespace).uppercase()
        require(code.matches(Regex("[0-9A-F]{6}"))) { "Enter the 6-character code shown on the TV" }
        try {
            val tvCertificate = s.session.peerCertificates.first() as X509Certificate
            val tvKey = tvCertificate.publicKey as? RSAPublicKey ?: throw IOException("TV returned an unsupported pairing key")
            val secret = pairingSecret(identityCertificate().publicKey as RSAPublicKey, tvKey, code)
            pairingRoundTrip(s, 40, Proto.Writer().bytes(1, secret).bytes(), 41)
            prefs.edit().putString(pinKey, fingerprint(tvCertificate)).apply()
        } finally {
            cancelPairing()
        }
        connect()
    }

    @Synchronized
    fun cancelPairing() {
        runCatching { pairingSocket?.close() }
        pairingSocket = null
    }

    fun connect() {
        if (loop?.isAlive == true) return
        stopped = false
        loop = Thread({
            var delayMs = 1_000L
            while (!stopped) {
                try {
                    session()
                    delayMs = 1_000
                } catch (_: Exception) {
                    connected = false
                    lastError = "Could not connect to the TV. Check that it is online and still paired."
                    closeSession()
                    try { Thread.sleep(delayMs) } catch (_: InterruptedException) { }
                    delayMs = (delayMs * 2).coerceAtMost(15_000)
                }
            }
        }, "android-tv-remote").also { it.isDaemon = true; it.start() }
    }

    fun close() {
        stopped = true
        cancelPairing()
        closeSession()
        loop?.interrupt()
        loop = null
        writes.shutdownNow()
    }

    fun sendKey(keyCode: Int, direction: Int = 3) {
        if (!connected) throw IOException("Not connected to the TV")
        writes.execute {
            try {
                val out = output ?: throw IOException("Not connected to the TV")
                synchronized(lock) {
                    Proto.write(out, Proto.Writer().bytes(10,
                        Proto.Writer().varint(1, keyCode.toLong()).varint(2, direction.toLong()).bytes()).bytes())
                }
                lastError = ""
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
            }
        }
    }

    fun sendText(text: String) {
        require(text.isNotEmpty()) { "Enter text to send" }
        val valueLength = text.length - 1
        val remoteText = Proto.Writer().varint(1, valueLength.toLong()).varint(2, valueLength.toLong()).string(3, text)
        val edit = Proto.Writer().varint(1, 1).bytes(2, remoteText.bytes())
        val batchEdit = Proto.Writer().varint(1, imeCounter.toLong()).varint(2, imeFieldCounter.toLong())
            .bytes(3, edit.bytes())
        enqueueMessage(21, batchEdit.bytes())
    }

    suspend fun startVoiceSession(): Int {
        if (!connected) throw IOException("Not connected to the TV")
        if (features and 8 == 0) throw IOException("This TV does not support Android TV Remote voice input")
        val response = CompletableDeferred<Int>()
        synchronized(voiceLock) {
            if (activeVoiceSession != null || waitingForVoiceSession != null) throw IOException("TV voice session already active")
            waitingForVoiceSession = response
        }
        try {
            sendKey(android.view.KeyEvent.KEYCODE_SEARCH)
            val sessionId = withTimeout(2_000) { response.await() }
            synchronized(voiceLock) { activeVoiceSession = sessionId; waitingForVoiceSession = null }
            enqueueMessage(30, Proto.Writer().varint(1, sessionId.toLong()).bytes())
            return sessionId
        } catch (error: Exception) {
            synchronized(voiceLock) { waitingForVoiceSession = null }
            throw error
        }
    }

    fun sendVoiceChunk(sessionId: Int, pcm8kMono16: ByteArray) {
        require(pcm8kMono16.isNotEmpty()) { "No microphone audio was captured" }
        require(activeVoiceSession == sessionId) { "TV voice session is no longer active" }
        val chunkSize = 20 * 1024
        var offset = 0
        while (offset < pcm8kMono16.size) {
            val end = minOf(offset + chunkSize, pcm8kMono16.size)
            var samples = pcm8kMono16.copyOfRange(offset, end)
            if (samples.size < 8 * 1024) samples = samples.copyOf(8 * 1024)
            enqueueMessage(31, Proto.Writer().varint(1, sessionId.toLong()).bytes(2, samples).bytes())
            offset = end
        }
    }

    fun endVoiceSession(sessionId: Int) {
        if (activeVoiceSession != sessionId) return
        enqueueMessage(32, Proto.Writer().varint(1, sessionId.toLong()).bytes())
        synchronized(voiceLock) { activeVoiceSession = null; waitingForVoiceSession = null }
    }

    private fun session() {
        val s = createSocket(host, 6466, 7_000)
        socket = s
        val expected = prefs.getString(pinKey, null) ?: throw IOException("Pair with this TV first")
        if (fingerprint(s.session.peerCertificates.first() as X509Certificate) != expected) {
            throw IOException("The TV identity changed; remove its pairing and pair again")
        }
        s.soTimeout = 30_000
        output = s.outputStream
        while (!stopped && !s.isClosed) {
            val message = Proto.read(s.inputStream)
            when {
                message.has(1) -> {
                    val requested = message.message(1).number(1, features.toLong()).toInt()
                    features = features and requested
                    val negotiated = features
                    Proto.write(s.outputStream, Proto.Writer().bytes(1,
                        Proto.Writer().varint(1, negotiated.toLong()).bytes(2,
                            Proto.Writer().string(1, Build.MODEL).string(2, Build.MANUFACTURER)
                                .varint(3, 1).string(4, "1").string(5, "my.home Remote").string(6, "1.0").bytes()).bytes()).bytes())
                }
                message.has(2) -> {
                    Proto.write(s.outputStream, Proto.Writer().bytes(2, Proto.Writer().varint(1, features.toLong()).bytes()).bytes())
                    connected = true
                    lastError = ""
                }
                message.has(8) -> Proto.write(s.outputStream,
                    Proto.Writer().bytes(9, Proto.Writer().varint(1, message.message(8).number(1, 0)).bytes()).bytes())
                message.has(21) -> {
                    val batch = message.message(21)
                    imeCounter = batch.number(1, imeCounter.toLong()).toInt()
                    imeFieldCounter = batch.number(2, imeFieldCounter.toLong()).toInt()
                }
                message.has(30) -> {
                    val sessionId = message.message(30).number(1, -1).toInt()
                    synchronized(voiceLock) { waitingForVoiceSession?.complete(sessionId) }
                }
            }
        }
        connected = false
        closeSession()
    }

    private fun pairingRoundTrip(s: SSLSocket, field: Int, payload: ByteArray, expectedField: Int) {
        Proto.write(s.outputStream, Proto.Writer().varint(1, 2).varint(2, 200).bytes(field, payload).bytes())
        val response = Proto.read(s.inputStream)
        val status = response.number(2, 0)
        if (status != 200L) throw IOException(if (status == 402L) "The TV rejected the pairing code" else "TV pairing error ($status)")
        if (!response.has(expectedField)) throw IOException("Unexpected pairing response from the TV")
    }

    private fun enqueueMessage(field: Int, payload: ByteArray) {
        if (!connected) throw IOException("Not connected to the TV")
        writes.execute {
            try {
                val out = output ?: throw IOException("Not connected to the TV")
                synchronized(lock) { Proto.write(out, Proto.Writer().bytes(field, payload).bytes()) }
                lastError = ""
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
            }
        }
    }

    private fun createSocket(targetHost: String, port: Int, timeoutMs: Int): SSLSocket {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) generateIdentity()
        val key = ks.getKey(alias, null) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        val keyManager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getCertificateChain(aliasValue: String?) = arrayOf(cert)
            override fun getPrivateKey(aliasValue: String?) = key
        }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trust), null) }
        val s = ssl.socketFactory.createSocket() as SSLSocket
        try {
            s.connect(InetSocketAddress(targetHost, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.startHandshake()
            return s
        } catch (error: Exception) {
            runCatching { s.close() }
            throw error
        }
    }

    private fun generateIdentity() {
        val now = System.currentTimeMillis()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .setRandomizedEncryptionRequired(false)
            .setCertificateSubject(X500Principal("CN=my.home Remote"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 86_400_000L))
            .setCertificateNotAfter(Date(now + 30L * 365 * 86_400_000L)).build())
        generator.generateKeyPair()
    }

    private fun identityCertificate(): X509Certificate {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) generateIdentity()
        return ks.getCertificate(alias) as X509Certificate
    }

    private fun closeSession() {
        connected = false
        output = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun pairingSecret(client: RSAPublicKey, tv: RSAPublicKey, code: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(client, tv).forEach { key ->
            digest.update(key.modulus.toUnsignedBytes())
            digest.update(key.publicExponent.toUnsignedBytes())
        }
        val suffix = code.substring(2)
        digest.update(ByteArray(2) { index -> suffix.substring(index * 2, index * 2 + 2).toInt(16).toByte() })
        val secret = digest.digest()
        if ((secret[0].toInt() and 0xff) != code.substring(0, 2).toInt(16)) throw IOException("Pairing code does not match the TV")
        return secret
    }

    private fun BigInteger.toUnsignedBytes(): ByteArray {
        val value = toByteArray()
        return if (value.size > 1 && value[0] == 0.toByte()) Arrays.copyOfRange(value, 1, value.size) else value
    }

    private fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(cert.encoded).joinToString("") { "%02x".format(it) }
}

/** Small protobuf subset used by Android TV Remote v2, framed with a varint length. */
private object Proto {
    class Writer {
        private val data = ByteArrayOutputStream()
        fun varint(field: Int, value: Long) = apply { raw(field.toLong() shl 3); raw(value) }
        fun string(field: Int, value: String) = bytes(field, value.toByteArray(StandardCharsets.UTF_8))
        fun message(field: Int, writer: Writer) = bytes(field, writer.bytes())
        fun bytes(field: Int, value: ByteArray) = apply { raw((field.toLong() shl 3) or 2); raw(value.size.toLong()); data.write(value) }
        fun bytes() = data.toByteArray()
        private fun raw(original: Long) { var value = original; while (value and -128L != 0L) { data.write(((value.toInt() and 127) or 128)); value = value ushr 7 }; data.write(value.toInt()) }
    }

    class Message private constructor(private val fields: Map<Int, List<Any>>) {
        fun has(field: Int) = fields.containsKey(field)
        fun number(field: Int, default: Long) = fields[field]?.firstOrNull() as? Long ?: default
        fun message(field: Int): Message = (fields[field]?.firstOrNull() as? ByteArray)?.let(::parse) ?: Message(emptyMap())
        companion object {
            fun parse(data: ByteArray): Message {
                val fields = mutableMapOf<Int, MutableList<Any>>()
                var offset = 0
                fun varint(): Long {
                    var result = 0L
                    var shift = 0
                    while (shift < 64) {
                        if (offset >= data.size) throw EOFException("Truncated protobuf message")
                        val b = data[offset++].toInt() and 0xff
                        result = result or ((b and 127).toLong() shl shift)
                        if (b and 128 == 0) return result
                        shift += 7
                    }
                    throw IOException("Invalid protobuf varint")
                }
                while (offset < data.size) {
                    val tag = varint().toInt()
                    val field = tag ushr 3
                    val value: Any = when (tag and 7) {
                        0 -> varint()
                        2 -> {
                            val size = varint().toInt()
                            if (size < 0 || offset + size > data.size) throw EOFException("Truncated protobuf field")
                            data.copyOfRange(offset, offset + size).also { offset += size }
                        }
                        1 -> {
                            if (offset + 8 > data.size) throw EOFException("Truncated protobuf fixed64 field")
                            data.copyOfRange(offset, offset + 8).also { offset += 8 }
                        }
                        5 -> {
                            if (offset + 4 > data.size) throw EOFException("Truncated protobuf fixed32 field")
                            data.copyOfRange(offset, offset + 4).also { offset += 4 }
                        }
                        else -> throw IOException("Unsupported protobuf wire type")
                    }
                    fields.getOrPut(field) { mutableListOf() }.add(value)
                }
                return Message(fields)
            }
        }
    }

    fun write(output: java.io.OutputStream, body: ByteArray) {
        val frame = ByteArrayOutputStream()
        var length = body.size
        while (length and 127 != 0) { frame.write((length and 127) or 128); length = length ushr 7 }
        frame.write(length)
        frame.write(body)
        output.write(frame.toByteArray())
        output.flush()
    }

    fun read(input: InputStream): Message {
        var length = 0
        var shift = 0
        while (shift <= 28) {
            val value = input.read()
            if (value < 0) throw EOFException("TV closed the connection")
            length = length or ((value and 127) shl shift)
            if (value and 128 == 0) break
            shift += 7
        }
        if (length !in 1..1_048_576) throw IOException("Invalid TV message size")
        val buffer = ByteArray(length)
        DataInputStream(input).readFully(buffer)
        return Message.parse(buffer)
    }
}
