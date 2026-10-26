package com.archie.cryptobot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import bot.FastSettings
import bot.ScalpSettings
import bot.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ApiKeys(val keyId: String, val secret: String)

/** Alpaca keys, encrypted with a key held in the Android Keystore. They never leave the phone except to Alpaca. */
object KeyVault {
    private const val ALIAS = "alpaca_keys"
    private const val PREFS = "vault"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    fun save(context: Context, keys: ApiKeys) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val data = cipher.doFinal(JSONObject().put("id", keys.keyId).put("secret", keys.secret).toString().toByteArray())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(data, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): ApiKeys? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = prefs.getString("iv", null) ?: return null
        val data = prefs.getString("data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))))
            ApiKeys(json.getString("id"), json.getString("secret"))
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}

/** Strategy settings, the on/off switch and the activity log. */
object BotStore {
    private const val PREFS = "bot"
    private const val MAX_LOG = 200

    val symbols = listOf("BTC/USD", "ETH/USD", "SOL/USD", "LTC/USD", "DOGE/USD")

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(c: Context): Settings {
        val p = prefs(c)
        val d = Settings()
        return try {
            Settings(
                symbol = p.getString("symbol", d.symbol)!!,
                fast = p.getInt("fast", d.fast),
                slow = p.getInt("slow", d.slow),
                maxPositionPct = p.getFloat("cap", d.maxPositionPct.toFloat()).toDouble(),
                stopLossPct = p.getFloat("stop", d.stopLossPct.toFloat()).toDouble(),
                mode = p.getString("mode", d.mode)!!,
                scalp = ScalpSettings(
                    rsiBuy = p.getFloat("s_rsi", d.scalp.rsiBuy.toFloat()).toDouble(),
                    takeProfitPct = p.getFloat("s_tp", d.scalp.takeProfitPct.toFloat()).toDouble(),
                    stopPct = p.getFloat("s_stop", d.scalp.stopPct.toFloat()).toDouble(),
                    maxHoldBars = p.getInt("s_hold", d.scalp.maxHoldBars),
                    trendFilter = p.getBoolean("s_trend", d.scalp.trendFilter),
                    entry = p.getString("s_entry", d.scalp.entry)!!,
                ),
                quick = FastSettings(
                    takeProfitPct = p.getFloat("f_tp", d.quick.takeProfitPct.toFloat()).toDouble(),
                    stopPct = p.getFloat("f_sl", d.quick.stopPct.toFloat()).toDouble(),
                    maxHoldMinutes = p.getInt("f_hold", d.quick.maxHoldMinutes),
                    minHourlyMove = p.getFloat("f_vol", d.quick.minHourlyMove.toFloat()).toDouble(),
                    entry = p.getString("f_entry", d.quick.entry)!!,
                ),
            )
        } catch (e: IllegalArgumentException) {
            d
        }
    }

    fun saveSettings(c: Context, s: Settings) = prefs(c).edit()
        .putString("symbol", s.symbol).putInt("fast", s.fast).putInt("slow", s.slow)
        .putFloat("cap", s.maxPositionPct.toFloat()).putFloat("stop", s.stopLossPct.toFloat())
        .putString("mode", s.mode)
        .putFloat("s_rsi", s.scalp.rsiBuy.toFloat()).putFloat("s_tp", s.scalp.takeProfitPct.toFloat())
        .putFloat("s_stop", s.scalp.stopPct.toFloat()).putInt("s_hold", s.scalp.maxHoldBars)
        .putBoolean("s_trend", s.scalp.trendFilter).putString("s_entry", s.scalp.entry)
        .putFloat("f_tp", s.quick.takeProfitPct.toFloat()).putFloat("f_sl", s.quick.stopPct.toFloat())
        .putInt("f_hold", s.quick.maxHoldMinutes).putFloat("f_vol", s.quick.minHourlyMove.toFloat())
        .putString("f_entry", s.quick.entry)
        .apply()

    /** The amount the bot trades with and measures profit against; 0 means "use the whole account". */
    fun startingAmount(c: Context) = prefs(c).getString("start_amount", "0")!!.toDouble()
    fun startTime(c: Context) = prefs(c).getLong("start_time", 0L)
    fun setStartingAmount(c: Context, amount: Double) = prefs(c).edit()
        .putString("start_amount", amount.toString()).putLong("start_time", System.currentTimeMillis()).apply()

    /** Latest status line from fast mode (it checks too often to log every check). */
    fun status(c: Context) = prefs(c).getString("status", null)
    fun setStatus(c: Context, text: String) = prefs(c).edit().putString("status", text).putLong("status_time", System.currentTimeMillis()).apply()
    fun statusTime(c: Context) = prefs(c).getLong("status_time", 0L)

    fun isEnabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

    data class LogEntry(val time: Long, val text: String)

    @Synchronized
    fun log(c: Context, text: String) {
        val arr = JSONArray(prefs(c).getString("log", "[]"))
        val out = JSONArray().put(JSONObject().put("t", System.currentTimeMillis()).put("m", text))
        for (i in 0 until minOf(arr.length(), MAX_LOG - 1)) out.put(arr.get(i))
        prefs(c).edit().putString("log", out.toString()).apply()
    }

    fun readLog(c: Context): List<LogEntry> {
        val arr = JSONArray(prefs(c).getString("log", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> LogEntry(o.getLong("t"), o.getString("m")) } }
    }
}
