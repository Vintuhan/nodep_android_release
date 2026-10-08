package com.nodep.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Локальный VPN: перехватывает DNS, режет домены из blocklist.
 * Остальной трафик не проксируем через туннель (только DNS query path).
 * Упрощённая схема: VPN interface + чтение пакетов, DNS на 53 → проверка имени.
 */
class BlockVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProtect()
            return START_NOT_STICKY
        }
        startProtect()
        return START_STICKY
    }

    private fun startProtect() {
        if (running.get()) return
        Blocklist.load(this)
        startForeground(NOTIF_ID, buildNotification())

        // Только DNS через VPN — остальной интернет без туннеля (не рвём связь).
        val builder = Builder()
            .setSession("nodep")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("10.0.0.1", 32)
            .setMtu(1500)
        try {
            builder.allowFamily(android.system.OsConstants.AF_INET)
        } catch (_: Exception) {}

        tun = builder.establish()
        if (tun == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        running.set(true)
        isRunning = true
        worker = thread(name = "nodep-vpn") { loop(tun!!) }
    }

    private fun stopProtect() {
        running.set(false)
        isRunning = false
        try { worker?.interrupt() } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun loop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteArray(32767)

        while (running.get()) {
            val len = try {
                input.read(packet)
            } catch (_: Exception) {
                break
            }
            if (len <= 0) continue

            // IPv4 only simple parse
            if (len < 28) continue
            val version = (packet[0].toInt() ushr 4) and 0xF
            if (version != 4) continue

            val ihl = (packet[0].toInt() and 0xF) * 4
            if (len < ihl + 8) continue
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 17) continue // UDP

            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            if (dstPort != 53 && srcPort != 53) continue

            val dnsOffset = ihl + 8
            if (len < dnsOffset + 12) continue

            if (dstPort == 53) {
                val name = parseDnsName(packet, dnsOffset, len) ?: continue
                if (Blocklist.isBlocked(name)) {
                    val response = buildNxDomain(packet, len, ihl)
                    if (response != null) {
                        try { output.write(response) } catch (_: Exception) {}
                    }
                    continue
                }
                // forward DNS to public resolver
                forwardDns(packet, len, ihl, output)
            }
        }
    }

    private fun forwardDns(packet: ByteArray, len: Int, ihl: Int, output: FileOutputStream) {
        val dnsPayloadLen = len - ihl - 8
        if (dnsPayloadLen <= 0) return
        val payload = packet.copyOfRange(ihl + 8, len)
        try {
            val socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 3000
            val server = InetAddress.getByName("1.1.1.1")
            socket.send(DatagramPacket(payload, payload.size, server, 53))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            socket.close()

            // Build IP+UDP response swapping addresses/ports — simplified: reuse header template
            val out = ByteArray(ihl + 8 + resp.length)
            System.arraycopy(packet, 0, out, 0, ihl + 8)
            // swap src/dst IP
            for (i in 0 until 4) {
                val a = out[12 + i]
                out[12 + i] = out[16 + i]
                out[16 + i] = a
            }
            // swap ports
            val sp0 = out[ihl]
            val sp1 = out[ihl + 1]
            out[ihl] = out[ihl + 2]
            out[ihl + 1] = out[ihl + 3]
            out[ihl + 2] = sp0
            out[ihl + 3] = sp1
            // UDP length
            val udpLen = 8 + resp.length
            out[ihl + 4] = ((udpLen ushr 8) and 0xFF).toByte()
            out[ihl + 5] = (udpLen and 0xFF).toByte()
            out[ihl + 6] = 0
            out[ihl + 7] = 0
            System.arraycopy(resp.data, resp.offset, out, ihl + 8, resp.length)
            // IP total length
            val total = ihl + 8 + resp.length
            out[2] = ((total ushr 8) and 0xFF).toByte()
            out[3] = (total and 0xFF).toByte()
            // clear checksums
            out[10] = 0
            out[11] = 0
            output.write(out, 0, total)
        } catch (_: Exception) {
            // drop
        }
    }

    private fun parseDnsName(packet: ByteArray, dnsOffset: Int, len: Int): String? {
        var i = dnsOffset + 12
        if (i >= len) return null
        val parts = ArrayList<String>()
        while (i < len) {
            val lab = packet[i].toInt() and 0xFF
            if (lab == 0) break
            if (lab and 0xC0 == 0xC0) break // compression pointer in question — rare
            if (lab > 63) return null
            i++
            if (i + lab > len) return null
            parts.add(String(packet, i, lab, Charsets.US_ASCII).lowercase())
            i += lab
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(".")
    }

    private fun buildNxDomain(request: ByteArray, reqLen: Int, ihl: Int): ByteArray? {
        // minimal: copy request, set QR=1, RCODE=3 (NXDOMAIN), swap IP/ports
        val out = request.copyOf(reqLen)
        for (i in 0 until 4) {
            val a = out[12 + i]
            out[12 + i] = out[16 + i]
            out[16 + i] = a
        }
        val sp0 = out[ihl]
        val sp1 = out[ihl + 1]
        out[ihl] = out[ihl + 2]
        out[ihl + 1] = out[ihl + 3]
        out[ihl + 2] = sp0
        out[ihl + 3] = sp1
        val dns = ihl + 8
        if (dns + 3 >= reqLen) return null
        // flags: QR=1, RCODE=3
        out[dns + 2] = (out[dns + 2].toInt() or 0x80).toByte()
        out[dns + 3] = ((out[dns + 3].toInt() and 0xF0) or 0x03).toByte()
        out[10] = 0
        out[11] = 0
        out[ihl + 6] = 0
        out[ihl + 7] = 0
        return out
    }

    private fun buildNotification(): Notification {
        val channelId = "nodep"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_logo)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopProtect()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.nodep.app.STOP"
        const val NOTIF_ID = 42
        @Volatile var isRunning: Boolean = false
    }
}
