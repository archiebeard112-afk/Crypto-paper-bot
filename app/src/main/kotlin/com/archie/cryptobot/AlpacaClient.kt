package com.archie.cryptobot

import bot.Bar
import bot.Fill
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

class NotPaperException(message: String) : IOException(message)

/**
 * Alpaca REST client. Trading calls can ONLY reach the paper endpoint: the URL is a constant
 * and the account is checked to be a paper account before any order is placed.
 * Market data is public and works without keys.
 */
class AlpacaClient(private val keys: ApiKeys?) {
    companion object {
        const val PAPER_URL = "https://paper-api.alpaca.markets"
        const val DATA_URL = "https://data.alpaca.markets"
    }

    private fun request(method: String, url: String, body: JSONObject? = null): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (keys != null) {
                conn.setRequestProperty("APCA-API-KEY-ID", keys.keyId)
                conn.setRequestProperty("APCA-API-SECRET-KEY", keys.secret)
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            if (code == 404) return null
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException("Alpaca $method ${URL(url).path} failed ($code): ${err.take(300)}")
            }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun pathSymbol(symbol: String) = symbol.replace("/", "")
    private fun sameSymbol(a: String, b: String) = pathSymbol(a) == pathSymbol(b)

    // ---- trading (paper only) ----

    fun account(): JSONObject {
        val acct = JSONObject(request("GET", "$PAPER_URL/v2/account") ?: throw IOException("No account"))
        if (!acct.optString("account_number").startsWith("PA")) {
            throw NotPaperException("This does not look like a paper account. The bot only trades paper money.")
        }
        return acct
    }

    fun position(symbol: String): JSONObject? =
        request("GET", "$PAPER_URL/v2/positions/${pathSymbol(symbol)}")?.let(::JSONObject)

    fun orders(symbol: String, status: String, after: Instant? = null): List<JSONObject> {
        var url = "$PAPER_URL/v2/orders?status=$status&limit=500&direction=desc"
        if (after != null) url += "&after=${enc(after.toString())}"
        val arr = JSONArray(request("GET", url) ?: "[]")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { sameSymbol(it.optString("symbol"), symbol) }
    }

    fun cancelOrder(id: String) {
        request("DELETE", "$PAPER_URL/v2/orders/$id")
    }

    fun closePosition(symbol: String): JSONObject? =
        request("DELETE", "$PAPER_URL/v2/positions/${pathSymbol(symbol)}")?.let(::JSONObject)

    fun buyNotional(symbol: String, dollars: Double): JSONObject = JSONObject(request("POST", "$PAPER_URL/v2/orders", JSONObject()
        .put("symbol", symbol).put("notional", "%.2f".format(java.util.Locale.US, dollars))
        .put("side", "buy").put("type", "market").put("time_in_force", "gtc"))!!)

    /** Resting stop-loss. Alpaca crypto has no plain stop orders, so this is a stop-limit. */
    fun stopLimitSell(symbol: String, qty: String, stop: Double, limit: Double): JSONObject =
        JSONObject(request("POST", "$PAPER_URL/v2/orders", JSONObject()
            .put("symbol", symbol).put("qty", qty).put("side", "sell").put("type", "stop_limit")
            .put("time_in_force", "gtc")
            .put("stop_price", price(stop)).put("limit_price", price(limit)))!!)

    private fun price(v: Double) = if (v >= 100) "%.2f".format(java.util.Locale.US, v) else "%.6f".format(java.util.Locale.US, v)

    /** Every filled order (all coins) since [after], oldest first. */
    fun filledOrdersSince(after: Instant): List<Fill> {
        val url = "$PAPER_URL/v2/orders?status=closed&limit=500&direction=asc&after=${enc(after.toString())}"
        val arr = JSONArray(request("GET", url) ?: "[]")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optDouble("filled_qty", 0.0) > 0 && !it.isNull("filled_at") }
            .map {
                Fill(normalize(it.getString("symbol")), it.getString("side"), it.getDouble("filled_qty"),
                    it.getDouble("filled_avg_price"), Instant.parse(it.getString("filled_at")).epochSecond)
            }
    }

    /** Alpaca reports some crypto symbols as "BTCUSD"; map them back to "BTC/USD". */
    private fun normalize(symbol: String) = BotStore.symbols.firstOrNull { sameSymbol(it, symbol) } ?: symbol

    /** Daily value of the whole paper account over [period] (e.g. "1M", "3M", "1A"). */
    fun portfolioHistory(period: String = "1M"): List<Pair<Long, Double>> {
        val json = JSONObject(request("GET", "$PAPER_URL/v2/account/portfolio/history?period=$period&timeframe=1D") ?: "{}")
        val ts = json.optJSONArray("timestamp") ?: return emptyList()
        val eq = json.optJSONArray("equity") ?: return emptyList()
        return (0 until minOf(ts.length(), eq.length()))
            .filter { !eq.isNull(it) && eq.getDouble(it) > 0 }
            .map { ts.getLong(it) to eq.getDouble(it) }
    }

    // ---- market data (public) ----

    fun dailyBars(symbol: String, start: Instant): List<Bar> {
        val bars = mutableListOf<Bar>()
        var token: String? = null
        do {
            var url = "$DATA_URL/v1beta3/crypto/us/bars?symbols=${enc(symbol)}&timeframe=1Day" +
                "&start=${enc(start.toString())}&limit=10000&sort=asc"
            if (token != null) url += "&page_token=${enc(token)}"
            val page = JSONObject(request("GET", url) ?: "{}")
            val arr = page.optJSONObject("bars")?.optJSONArray(symbol) ?: JSONArray()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                bars += Bar(Instant.parse(b.getString("t")).epochSecond, b.getDouble("o"), b.getDouble("h"), b.getDouble("l"), b.getDouble("c"))
            }
            token = page.optString("next_page_token").takeIf { it.isNotEmpty() && it != "null" }
        } while (token != null)
        return bars
    }

    fun latestPrices(symbols: List<String>): Map<String, Double> {
        val json = JSONObject(request("GET", "$DATA_URL/v1beta3/crypto/us/latest/trades?symbols=${enc(symbols.joinToString(","))}") ?: "{}")
        val trades = json.optJSONObject("trades") ?: return emptyMap()
        return symbols.filter { trades.has(it) }.associateWith { trades.getJSONObject(it).getDouble("p") }
    }

    fun latestPrice(symbol: String): Double {
        val json = JSONObject(request("GET", "$DATA_URL/v1beta3/crypto/us/latest/trades?symbols=${enc(symbol)}") ?: "{}")
        return json.getJSONObject("trades").getJSONObject(symbol).getDouble("p")
    }
}
