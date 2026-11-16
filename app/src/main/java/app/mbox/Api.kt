package app.mbox

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MovieBox (aoneroom) BFF client.
 *
 * Request signing = HMAC-MD5 over:
 *   METHOD \n accept \n content-type \n bodyLen \n ts \n md5(body) \n path?sortedQuery
 * sent as  x-tr-signature: "<ts>|2|<base64>"
 */
object Api {

    private const val BASE = "https://api6.aoneroom.com/wefeed-mobile-bff"
    private const val KEY_B64 = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
    private const val CTYPE = "application/json;charset=UTF-8"
    private const val ACCEPT = "application/json"
    private const val UA = "com.community.mbox.in/50020042 (Linux; Android 14; Pixel 7; Cronet/133.0.6876.3)"
    const val PLAYER_UA = "ExoPlayerLib/2.19.1"

    private val jsonMedia = CTYPE.toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var deviceId: String = ""
    private var gaid: String = ""
    private var prefs: SharedPreferences? = null

    @Volatile
    private var token: String? = null

    fun init(ctx: Context) {
        val sp = ctx.getSharedPreferences("mbox", Context.MODE_PRIVATE)
        prefs = sp
        deviceId = sp.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            sp.edit().putString("device_id", it).apply()
        }
        gaid = sp.getString("gaid", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("gaid", it).apply()
        }
        token = sp.getString("token", null)?.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- signing

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hmacMd5Base64(data: String): String {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(Base64.decode(KEY_B64, Base64.DEFAULT), "HmacMD5"))
        return Base64.encodeToString(mac.doFinal(data.toByteArray()), Base64.NO_WRAP)
    }

    private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun canonical(method: String, url: String, body: ByteArray, ts: String): String {
        // Python parity: urlsplit().path + sorted(parse_qsl(query)) with values percent-decoded.
        val uri = URI(url)
        val rawQuery = uri.rawQuery ?: ""
        val pairs = if (rawQuery.isEmpty()) emptyList() else rawQuery.split("&")
            .filter { it.isNotEmpty() }
            .map { part ->
                val i = part.indexOf('=')
                val k = if (i < 0) part else part.substring(0, i)
                val v = if (i < 0) "" else part.substring(i + 1)
                dec(k) to dec(v)
            }
        val qs = pairs.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        val target = uri.rawPath + if (qs.isEmpty()) "" else "?$qs"
        val len = if (body.isEmpty()) "" else body.size.toString()
        val bodyHash = if (body.isEmpty()) "" else md5Hex(body.copyOfRange(0, minOf(body.size, 102400)))
        return listOf(method.uppercase(), ACCEPT, CTYPE, len, ts, bodyHash, target).joinToString("\n")
    }

    private fun clientInfo(): String = JSONObject()
        .put("package_name", "com.community.mbox.in")
        .put("version_name", "4.0.03.1029.03")
        .put("version_code", 50020134)
        .put("os", "android")
        .put("os_version", "14")
        .put("device_id", deviceId)
        .put("gaid", gaid)
        .put("brand", "Google")
        .put("model", "Pixel 7")
        .put("lan", "en")
        .put("install_ch", "ps")
        .put("install_store", "ps")
        .toString()

    // ---------------------------------------------------------------- transport

    private suspend fun call(path: String, method: String = "GET", bodyJson: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            var last: Exception? = null
            repeat(3) { attempt ->
                try {
                    return@withContext callOnce(path, method, bodyJson)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
                delay(700L * (attempt + 1))
            }
            throw last ?: java.io.IOException("request failed: $path")
        }

    private fun callOnce(path: String, method: String, bodyJson: String?): JSONObject {
        val url = BASE + path
        val body = (bodyJson ?: "").toByteArray()
        val ts = System.currentTimeMillis().toString()
        val builder = Request.Builder().url(url)
        if (method == "POST") builder.post(body.toRequestBody(jsonMedia))

        builder.header("User-Agent", UA)
        builder.header("Accept", ACCEPT)
        builder.header("Content-Type", CTYPE)
        builder.header("x-client-token", "$ts," + md5Hex(ts.reversed().toByteArray()))
        builder.header("x-tr-signature", "$ts|2|" + hmacMd5Base64(canonical(method, url, body, ts)))
        builder.header("x-client-info", clientInfo())
        builder.header("x-client-status", "0")
        token?.let { builder.header("Authorization", "Bearer $it") }

        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
        }
    }

    /** Fetches (and caches) a guest token. Search/detail require one; playback does not. */
    suspend fun ensureToken() {
        if (token != null) return
        runCatching {
            val data = call("/user-api/visitor-login", "POST", JSONObject().put("deviceId", deviceId).toString())
            val fresh = data.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }
            if (fresh != null) {
                token = fresh
                prefs?.edit()?.putString("token", fresh)?.apply()
            }
        }
    }

    // ---------------------------------------------------------------- parsing

    private fun subjectOf(o: JSONObject): Subject = Subject(
        id = o.optString("subjectId"),
        title = o.optString("title"),
        type = o.optInt("subjectType", 1),
        cover = o.optJSONObject("cover")?.optString("url").orEmpty(),
        year = o.optString("releaseDate").take(4),
        duration = o.optString("duration"),
        genre = o.optString("genre"),
        rating = o.optString("imdbRatingValue"),
        desc = o.optString("description").let { if (it == "null") "" else it },
    )

    private fun listOfSubjects(arr: org.json.JSONArray?): List<Subject> {
        if (arr == null) return emptyList()
        val out = ArrayList<Subject>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optJSONObject("subject") ?: o
            if (s.optString("subjectId").isNotEmpty()) out.add(subjectOf(s))
        }
        return out
    }

    // ---------------------------------------------------------------- endpoints

    suspend fun hotSections(): List<HotSection> = withContext(Dispatchers.IO) {
        val data = call("/subject-api/search-rank/v2?host=api6.aoneroom.com")
        val hot = data.optJSONObject("data")?.optJSONArray("hot") ?: return@withContext emptyList()
        val out = ArrayList<HotSection>()
        for (i in 0 until hot.length()) {
            val sec = hot.optJSONObject(i) ?: continue
            val items = listOfSubjects(sec.optJSONArray("items"))
            if (items.isNotEmpty()) out.add(HotSection(sec.optString("name"), items))
        }
        out
    }

    suspend fun trending(page: Int = 1): List<Subject> = withContext(Dispatchers.IO) {
        val data = call(
            "/subject-api/trending/v2?host=api6.aoneroom.com",
            "POST",
            JSONObject().put("page", page).toString(),
        )
        listOfSubjects(data.optJSONObject("data")?.optJSONArray("items"))
    }

    suspend fun search(keyword: String): List<Subject> = withContext(Dispatchers.IO) {
        ensureToken()
        val body = JSONObject()
            .put("keyword", keyword)
            .put("type", 0)
            .put("page", 1)
            .put("pageSize", 30)
            .toString()
        val data = call("/subject-api/search", "POST", body)
        listOfSubjects(data.optJSONObject("data")?.optJSONArray("items"))
    }

    suspend fun detail(subjectId: String): Subject = withContext(Dispatchers.IO) {
        ensureToken()
        val data = call("/subject-api/get?subjectId=$subjectId")
        val o = data.optJSONObject("data") ?: JSONObject()
        subjectOf(o)
    }

    suspend fun seasons(subjectId: String): List<Season> = withContext(Dispatchers.IO) {
        ensureToken()
        val data = call("/subject-api/season-info/v2?subjectId=$subjectId")
        val arr = data.optJSONObject("data")?.optJSONArray("seasons") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Season(it.optInt("se"), it.optInt("maxEp")) }
        }
    }

    suspend fun resources(subjectId: String, se: Int, ep: Int): List<StreamRes> =
        withContext(Dispatchers.IO) {
            val data = call("/subject-api/resource/v2?subjectId=$subjectId&se=$se&ep=$ep")
            val arr = data.optJSONObject("data")?.optJSONArray("list") ?: return@withContext emptyList()
            val out = ArrayList<StreamRes>()
            val seen = HashSet<Int>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val itemEp = o.optInt("ep", 0)
                if (se != 0 && ep != 0 && itemEp != 0 && itemEp != ep) continue
                val res = o.optInt("resolution", 0)
                if (res <= 0 || !seen.add(res)) continue
                val src = o.optString("sourceUrl")
                if (src.isEmpty()) continue
                out.add(
                    StreamRes(
                        resolution = res,
                        codec = o.optString("codecName"),
                        // resource/v2 reports size in KB; some payloads use bytes.
                        size = (o.optString("size").toLongOrNull() ?: 0L).let { if (it in 1..10_000_000L) it * 1000L else it },
                        duration = o.optInt("duration", 0),
                        name = src.substringAfterLast('/'),
                        sourceUrl = src,
                    )
                )
            }
            out.sortedWith(compareByDescending<StreamRes> { it.resolution })
        }

    suspend fun sniff(sourceUrl: String, subjectId: String): String? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(sourceUrl, "UTF-8")
        val data = call("/sniff/config?host=api6.aoneroom.com&linkUrl=$encoded&subjectId=$subjectId")
        data.optJSONObject("data")?.optString("downloadUrl")?.takeIf { it.isNotEmpty() }
    }

    /** resources -> sniff for the requested resolution (falls back to the best one). */
    suspend fun stream(subjectId: String, se: Int, ep: Int, resolution: Int? = null): Pair<String, StreamRes>? {
        val list = resources(subjectId, se, ep)
        if (list.isEmpty()) return null
        val pick = list.firstOrNull { resolution != null && it.resolution == resolution } ?: list.first()
        val url = runCatching { sniff(pick.sourceUrl, subjectId) }.getOrNull() ?: return null
        return url to pick
    }
}
