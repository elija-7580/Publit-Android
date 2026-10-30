package io.github.elija7580.publit.tickets

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional Google Wallet export (GoogleWallet.swift). The issuer's key stays on the signing server
 * (services/google-wallet-signer), never in this app. Binary railway codes stay in Publit.
 */
object GoogleWallet {
    fun endpoint(ctx: Context): String? = runCatching {
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
        info.metaData?.getString("publit.walletSignerUrl")
    }.getOrNull()?.takeIf { it.startsWith("https://") && URL(it).host.isNotEmpty() }

    fun available(ctx: Context, p: TicketPass): Boolean {
        val code = p.barcode ?: return false
        if (endpoint(ctx) == null || code.payload != null) return false
        val text = code.message ?: return false
        return text.isNotEmpty() && text.toByteArray().size <= 4096 && code.format in listOf(
            TicketPass.Barcode.Format.QR, TicketPass.Barcode.Format.AZTEC, TicketPass.Barcode.Format.PDF417, TicketPass.Barcode.Format.CODE128)
    }

    /** The Google "save" link for this pass; only title, operator, route, dates and the static code are sent. */
    suspend fun export(ctx: Context, p: TicketPass): String = withContext(Dispatchers.IO) {
        val endpoint = endpoint(ctx) ?: error("not configured")
        val code = p.barcode ?: error("no code")
        val format = when (code.format) {
            TicketPass.Barcode.Format.QR -> "qr"; TicketPass.Barcode.Format.AZTEC -> "aztec"
            TicketPass.Barcode.Format.PDF417 -> "pdf417"; else -> "code128"
        }
        val body = JSONObject().put("id", p.id).put("title", p.displayTitle).put("operator", p.operatorName ?: "Publit")
            .put("barcode", JSONObject().put("type", format).put("value", code.message ?: ""))
        p.origin?.let { body.put("origin", it) }
        p.destination?.let { body.put("destination", it) }
        p.validFrom?.let { body.put("validFrom", it.toString()) }
        p.validUntil?.let { body.put("validUntil", it.toString()) }
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 25_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val url = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("saveUrl")
            val u = URL(url)
            check(u.protocol == "https" && u.host == "pay.google.com" && u.path.startsWith("/gp/v/save/"))
            url
        } finally { c.disconnect() }
    }
}
