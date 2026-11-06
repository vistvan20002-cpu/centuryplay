package com.airplay.streamer.engine

import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.util.LogServer
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import kotlin.concurrent.thread

/** Proven Android-native AirPlay 2/PTP sender used for Sonos/IKEA receivers. */
class NativePtpSink(
    private val device: AirPlayDevice,
    private val senderName: String,
    private val nativeLibraryDir: String,
    private val runtimeDir: File,
) : AudioSink {
    override val key = "native-ptp:${device.identity}"
    override val displayName = device.displayName
    override var listener: AudioSink.Listener? = null
    override var offsetMs: Int = 0

    private var process: Process? = null
    private var input: java.io.OutputStream? = null
    private var commandPipe: File? = null
    @Volatile private var running = false

    override fun connect(context: StreamContext) {
        val executable = File(nativeLibraryDir, "libcliairplay.so")
        check(executable.canExecute()) { "native PTP sender is missing" }
        val localIp = Socket(device.host, 7000).use { it.localAddress.hostAddress }
        val runDir = File(runtimeDir, "centuryplay-ptp").apply { mkdirs() }
        commandPipe = File(runDir, "${device.identity.replace(Regex("[^A-Za-z0-9]"), "")}.cmd").apply { delete() }
        val txt = device.features.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val latency = (context.latencyFrames * 1000 / context.sampleRate).coerceAtLeast(1200)
        val args = listOf(
            executable.absolutePath, "--protocol", "airplay2", "--ap2-native", "--timing", "ptp",
            "--port", "7000", "--if", localIp, "--publish-ip", localIp, "--name", senderName,
            "--hostname", "Sonos-${device.deviceId.replace(":", "")}.local", "--latency", latency.toString(),
            "--samplerate", "44100", "--bitdepth", "16", "--channels", "2", "--txt", txt,
            "--cmdpipe", commandPipe!!.absolutePath, device.host,
        )
        process = ProcessBuilder(args).apply {
            environment()["LD_LIBRARY_PATH"] = nativeLibraryDir
            redirectErrorStream(true)
        }.start()
        input = process!!.outputStream
        running = true
        thread(name = "NativePtpLog-$displayName", isDaemon = true) {
            process!!.inputStream.bufferedReader().useLines { lines ->
                lines.filter { it.contains("ERROR") || it.contains("WARN") || it.contains("failed") }
                    .forEach { LogServer.log("Native PTP: $it") }
            }
        }
        thread(name = "NativePtpStart-$displayName", isDaemon = true) {
            repeat(120) {
                if (!running || commandPipe!!.exists()) return@repeat
                Thread.sleep(50)
            }
            sendCommand("START_UNIX_MS=${System.currentTimeMillis() + 2200}\nACTION=START\n")
        }
    }

    override fun send(packet: AudioPacket) {
        if (!running) return
        try {
            input?.write(packet.pcm)
        } catch (error: Exception) {
            running = false
            listener?.onSinkDisconnected(this, SinkError.DISCONNECTED, error.message)
        }
    }

    override fun setVolume(volume: Float) = sendCommand("VOLUME=${(volume.coerceIn(0f, 1f) * 100).toInt()}\n")

    override fun setMetadata(metadata: TrackMetadata) {
        sendCommand("TITLE=${metadata.title.orEmpty()}\nARTIST=${metadata.artist.orEmpty()}\nALBUM=${metadata.album.orEmpty()}\nACTION=SENDMETA\n")
    }

    override fun disconnect() {
        running = false
        sendCommand("ACTION=DISCONNECT\n")
        runCatching { input?.close() }
        process?.destroy()
        commandPipe?.delete()
        input = null
        process = null
    }

    override fun stats() = "native PTP"

    private fun sendCommand(command: String) {
        val pipe = commandPipe ?: return
        if (!pipe.exists()) return
        runCatching { FileOutputStream(pipe, true).use { it.write(command.toByteArray()) } }
    }
}
