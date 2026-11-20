package app.mbox

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    // Real playback comes from the web (h5) BFF, not the mobile play-info endpoints: the mobile
    // ones answer with a 23s "Upgrade Guide" promo for every title. The h5 endpoint is unsigned but
    // is gated on the Referer header of the web player page, and the CDN wants that same Referer.
    private const val H5_BASE = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    const val WEB_UA =
        "Mozilla/5.0 (Linux; Android 12; M2004J19C) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val WEB_ORIGIN = "https://movie-box.co"

    /** Per-subject web slug (`detailPath`); needed by the h5 player calls. */
    private val slugCache = HashMap<String, String>()

    fun webReferer(slug: String): String = "$WEB_ORIGIN/movies/$slug"

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

    private fun clean(v: String?): String = if (v == null || v == "null") "" else v

    private fun staffOf(arr: org.json.JSONArray?): List<Staff> {
        if (arr == null) return emptyList()
        val out = ArrayList<Staff>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = clean(o.optString("name"))
            if (name.isEmpty()) continue
            out.add(
                Staff(
                    id = o.optString("staffId"),
                    name = name,
                    character = clean(o.optString("character")),
                    avatar = clean(o.optString("avatarUrl")),
                )
            )
        }
        return out
    }

    private fun subjectOf(o: JSONObject): Subject = Subject(
        id = o.optString("subjectId"),
        title = o.optString("title"),
        type = o.optInt("subjectType", 1),
        cover = o.optJSONObject("cover")?.optString("url").orEmpty(),
        year = o.optString("releaseDate").take(4),
        duration = clean(o.optString("duration")),
        genre = clean(o.optString("genre")),
        rating = o.optString("imdbRatingValue"),
        country = clean(o.optString("countryName")),
        badge = clean(o.optString("corner")),
        desc = clean(o.optString("description")),
        aka = clean(o.optString("aka")),
        language = clean(o.optString("language")),
        viewers = if (o.optString("viewers").isEmpty() || o.optString("viewers") == "null") "" else o.optString("viewers"),
        contentRating = clean(o.optString("contentRating")),
        wantCount = o.optInt("wantToSeeCount"),
        seenCount = o.optInt("haveSeenCount"),
        seasonCount = o.optInt("seNum"),
        staff = staffOf(o.optJSONArray("staffList")),
        subtitleLangs = clean(o.optString("subtitles")).split(",")
            .map { it.trim() }.filter { it.isNotEmpty() },
        trailer = o.optJSONObject("trailer")?.optJSONObject("VideoAddress")?.optString("url").orEmpty(),
        still = o.optJSONObject("stills")?.optString("url").orEmpty(),
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

    /** Plain search (kept for quick callers); always relevance-ranked. */
    suspend fun search(keyword: String): List<Subject> {
        val page = searchV2(keyword, "MovieTV").takeIf { it.subjects.isNotEmpty() }
            ?: searchV2(keyword, "All")
        if (page.subjects.isNotEmpty()) return rank(page.subjects, keyword)
        return withContext(Dispatchers.IO) {
            ensureToken()
            val body = JSONObject()
                .put("keyword", keyword)
                .put("type", 0)
                .put("page", 1)
                .put("pageSize", 30)
                .toString()
            val data = runCatching { call("/subject-api/search", "POST", body) }.getOrNull()
                ?: return@withContext emptyList()
            rank(listOfSubjects(data.optJSONObject("data")?.optJSONArray("items")), keyword)
        }
    }

    // ---------------------------------------------------------------- search

    /** Lower is not better: higher score wins. Bracket tags like "[Hindi]" are ignored. */
    private fun relevance(title: String, query: String): Int {
        fun norm(v: String) = v.lowercase()
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9\u0980-\u09FF ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val t = norm(title)
        val q = norm(query)
        if (t.isEmpty() || q.isEmpty()) return 0
        val tParts = t.split(' ')
        val qParts = q.split(' ').filter { it.isNotEmpty() }
        if (qParts.isEmpty()) return 0
        var hits = 0
        for (part in qParts) {
            if (tParts.any { it == part }) hits += 2
            else if (tParts.any { it.startsWith(part) }) hits += 1
        }
        return when {
            t == q -> 1000
            t.startsWith(q) -> 700
            t.contains(q) -> 500
            hits == 0 -> 0
            else -> 100 + hits * 25 + qParts.count { t.contains(it) } * 10
        }
    }

    /** Ranks a result list against the query and drops blocks with no textual match at all. */
    private fun rank(list: List<Subject>, query: String): List<Subject> {
        val scored = list.map { it to relevance(it.title, query) }
        val any = scored.any { it.second > 0 }
        val kept = if (any) scored.filter { it.second > 0 } else scored
        return kept
            .sortedWith(
                compareByDescending<Pair<Subject, Int>> { it.second }
                    .thenByDescending { it.first.rating.toDoubleOrNull() ?: 0.0 }
                    .thenByDescending { it.first.year.toIntOrNull() ?: 0 }
            )
            .map { it.first }
    }

    /** Search with the app's own tabs (All / Movie / TV / Music / People) and grouped results. */
    suspend fun searchV2(keyword: String, tabId: String = "All"): SearchPage =
        withContext(Dispatchers.IO) {
            ensureToken()
            val body = JSONObject()
                .put("keyword", keyword)
                .put("page", 1)
                .put("pageSize", 30)
                .put("type", 0)
                .put("tabId", tabId)
                .toString()
            val data = runCatching { call("/subject-api/search/v2", "POST", body) }
                .getOrElse { return@withContext SearchPage() }
            val payload = data.optJSONObject("data") ?: return@withContext SearchPage()

            val tabsArr = payload.optJSONArray("tabs")
            val tabs = ArrayList<SearchTab>()
            if (tabsArr != null) {
                for (i in 0 until tabsArr.length()) {
                    val t = tabsArr.optJSONObject(i) ?: continue
                    val subsArr = t.optJSONArray("subs")
                    val subs = ArrayList<SearchTab>()
                    if (subsArr != null) {
                        for (k in 0 until subsArr.length()) {
                            val st = subsArr.optJSONObject(k) ?: continue
                            val stId = st.optString("tabId")
                            if (stId.isNotEmpty() && stId != t.optString("tabId")) {
                                subs.add(SearchTab(stId, st.optString("name")))
                            }
                        }
                    }
                    tabs.add(SearchTab(t.optString("tabId"), t.optString("name"), subs))
                }
            }
            val groups = ArrayList<SearchGroup>()
            val results = payload.optJSONArray("results")
            if (results != null) {
                for (i in 0 until results.length()) {
                    val g = results.optJSONObject(i) ?: continue
                    val raw = ArrayList<Subject>()
                    val subjArr = g.optJSONArray("subjects")
                    if (subjArr != null) {
                        for (j in 0 until subjArr.length()) {
                            val o = subjArr.optJSONObject(j) ?: continue
                            val s = o.optJSONObject("subject") ?: o
                            if (s.optString("subjectId").isNotEmpty()) raw.add(subjectOf(s))
                        }
                    }
                    val people = ArrayList<Staff>()
                    val staffArr = g.optJSONArray("staffs")
                    if (staffArr != null) {
                        for (k in 0 until staffArr.length()) {
                            val so = staffArr.optJSONObject(k) ?: continue
                            val nm = clean(so.optString("name"))
                            if (nm.isEmpty()) continue
                            people.add(
                                Staff(
                                    id = so.optString("staffId"),
                                    name = nm,
                                    character = clean(so.optString("character")),
                                    avatar = clean(so.optString("avatarUrl")),
                                )
                            )
                        }
                    }
                    if (raw.isEmpty() && people.isEmpty()) continue
                    // Only full titles are meaningful for the Movie/Series tabs.
                    val filtered = when (tabId) {
                        "Movie" -> raw.filter { it.type == 1 }
                        "TV" -> raw.filter { it.type == 2 }
                        else -> raw
                    }
                    if (filtered.isEmpty()) continue
                    val ordered = if (tabId == "All" || tabId == "MovieTV" || tabId == "Movie" || tabId == "TV") {
                        rank(filtered, keyword)
                    } else filtered
                    groups.add(SearchGroup(g.optString("title"), ordered, people))
                }
            }
            SearchPage(tabs, groups)
        }

    /** Subtitle tracks for one episode; the endpoint needs resource/post ids from resource/v2. */
    suspend fun captionsFor(subjectId: String, se: Int, ep: Int): List<Caption> =
        withContext(Dispatchers.IO) {
            val res = runCatching { resources(subjectId, se, ep) }.getOrDefault(emptyList())
            val hit = res.filter { it.resourceId.isNotEmpty() }.maxByOrNull { it.resolution }
                ?: return@withContext emptyList()
            if (hit.resourceId.isEmpty()) return@withContext emptyList()
            val path = "/subject-api/get-ext-captions?host=api6.aoneroom.com&subjectId=$subjectId" +
                "&se=$se&ep=$ep&resourceId=${hit.resourceId}&postId=${hit.postId}"
            val data = runCatching { call(path) }.getOrNull()?.optJSONObject("data")
                ?: return@withContext emptyList()
            val arr = data.optJSONArray("extCaptions") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isEmpty()) null else Caption(
                    id = o.optString("id"),
                    lang = o.optString("lan"),
                    name = clean(o.optString("lanName")),
                    url = url,
                )
            }
        }

    /** Search landing data: hot boards + "everyone is searching" words. */
    suspend fun searchRank(): SearchRank = withContext(Dispatchers.IO) {
        ensureToken()
        val data = runCatching {
            call("/subject-api/search-rank/v2?host=api6.aoneroom.com")
        }.getOrNull()?.optJSONObject("data") ?: return@withContext SearchRank()
        val sections = ArrayList<HotSection>()
        val hot = data.optJSONArray("hot")
        if (hot != null) {
            for (i in 0 until hot.length()) {
                val sec = hot.optJSONObject(i) ?: continue
                val items = listOfSubjects(sec.optJSONArray("items"))
                if (items.isNotEmpty()) sections.add(HotSection(clean(sec.optString("name")), items))
            }
        }
        val words = ArrayList<String>()
        val everyone = data.optJSONArray("everyoneSearch")
        if (everyone != null) {
            for (i in 0 until everyone.length()) {
                val o = everyone.optJSONObject(i) ?: continue
                val w = clean(o.optString("word")).ifEmpty { clean(o.optString("name")) }
                if (w.isNotEmpty()) words.add(w)
            }
        }
        SearchRank(sections, words)
    }

    /** Genre/country/year/sort lists used by the filter sheet. */
    suspend fun filterOptions(): FilterOptions = withContext(Dispatchers.IO) {
        ensureToken()
        val data = runCatching { call("/subject-api/filter-items?host=api6.aoneroom.com") }
            .getOrNull()?.optJSONObject("data") ?: return@withContext FilterOptions()
        val types = data.optJSONArray("typeList") ?: return@withContext FilterOptions()
        var genres = emptyList<String>()
        var countries = emptyList<String>()
        var years = emptyList<String>()
        var sorts = emptyList<String>()
        for (i in 0 until types.length()) {
            val items = types.optJSONObject(i)?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val f = items.optJSONObject(j) ?: continue
                val vals = f.optJSONArray("filterVals") ?: continue
                val list = (0 until vals.length()).map { vals.optString(it) }
                    .filter { it.isNotEmpty() && it != "All" }
                when (f.optString("filterType")) {
                    "genre" -> genres = list
                    "country" -> countries = list
                    "year" -> years = list
                    "sort" -> sorts = list
                }
            }
        }
        FilterOptions(genres, countries, years, sorts)
    }

    /** Browse with filters, straight from the web filter page (same one the site uses). */
    suspend fun filtered(
        subjectType: Int = 0,
        genre: String = "",
        country: String = "",
        year: String = "",
        sort: String = "",
        page: Int = 1,
    ): List<Subject> = withContext(Dispatchers.IO) {
        val sb = StringBuilder("/filter?subjectType=$subjectType&page=$page&perPage=30")
        if (genre.isNotEmpty()) sb.append("&genre=").append(URLEncoder.encode(genre, "UTF-8"))
        if (country.isNotEmpty()) sb.append("&country=").append(URLEncoder.encode(country, "UTF-8"))
        if (year.isNotEmpty()) sb.append("&year=").append(URLEncoder.encode(year, "UTF-8"))
        if (sort.isNotEmpty()) sb.append("&sort=").append(URLEncoder.encode(sort, "UTF-8"))
        val data = runCatching { h5Catalog(sb.toString()) }.getOrNull()?.optJSONObject("data")
            ?: return@withContext emptyList()
        val holder = data.optJSONObject("subjectList") ?: return@withContext emptyList()
        listOfSubjects(holder.optJSONArray("items"))
    }

    /** Ranking boards ("TOP Movies", "Bollywood", …). */
    suspend fun rankBoards(): List<RankBoard> = withContext(Dispatchers.IO) {
        ensureToken()
        val data = runCatching { call("/tab/ranking-list?host=api6.aoneroom.com") }
            .getOrNull()?.optJSONObject("data") ?: return@withContext emptyList()
        val arr = data.optJSONArray("categoryList") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("type")
            if (id.isEmpty()) null else RankBoard(id, clean(o.optString("name")))
        }
    }

    /** Titles inside one ranking board. */
    suspend fun rankSubjects(boardId: String, page: Int = 1): List<Subject> =
        withContext(Dispatchers.IO) {
            ensureToken()
            val data = runCatching {
                call("/tab/ranking-list?host=api6.aoneroom.com&type=$boardId&page=$page&perPage=30")
            }.getOrNull()?.optJSONObject("data") ?: return@withContext emptyList()
            val arr = data.optJSONArray("subjects") ?: data.optJSONArray("items")
                ?: data.optJSONArray("subjectList") ?: return@withContext emptyList()
            listOfSubjects(arr)
        }

    /** Filmography of one person (`staff-subject-list`). */
    suspend fun staffSubjects(staffId: String, page: Int = 1): List<Subject> =
        withContext(Dispatchers.IO) {
            ensureToken()
            val data = runCatching {
                call("/subject-api/staff-subject-list?host=api6.aoneroom.com&staffId=$staffId" +
                    "&page=$page&pageSize=30")
            }.getOrNull()?.optJSONObject("data") ?: return@withContext emptyList()
            listOfSubjects(data.optJSONArray("items"))
        }

    /** People who worked with this person (`staff-related`). */
    suspend fun staffRelated(staffId: String, page: Int = 1): List<Staff> =
        withContext(Dispatchers.IO) {
            ensureToken()
            val data = runCatching {
                call("/subject-api/staff-related?host=api6.aoneroom.com&staffId=$staffId" +
                    "&page=$page&pageSize=20")
            }.getOrNull()?.optJSONObject("data") ?: return@withContext emptyList()
            val arr = data.optJSONArray("items") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = clean(o.optString("name"))
                if (name.isBlank()) null else Staff(
                    id = o.optString("staffId"),
                    name = name,
                    character = clean(o.optString("character")),
                    avatar = clean(o.optString("avatarUrl")),
                )
            }
        }

    /** Person headline from `staff-info`. */
    suspend fun staffInfo(staffId: String): Staff = withContext(Dispatchers.IO) {
        ensureToken()
        val o = runCatching {
            call("/subject-api/staff-info?host=api6.aoneroom.com&staffId=$staffId")
        }.getOrNull()?.optJSONObject("data") ?: return@withContext Staff(id = staffId)
        Staff(
            id = staffId,
            name = clean(o.optString("name")),
            character = (o.optJSONArray("staffTypes")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }.joinToString(", ")
            }).orEmpty(),
            avatar = clean(o.optString("avatarUrl")),
        )
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

    /** Audio/dub tracks for a subject; each track is a separate subjectId with its own resources. */
    suspend fun dubs(subjectId: String): List<Dub> = withContext(Dispatchers.IO) {
        ensureToken()
        val data = call("/subject-api/dub-info?host=api6.aoneroom.com&subjectId=$subjectId")
        val arr = data.optJSONObject("data")?.optJSONArray("dubs") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val sid = o.optString("subjectId")
            if (sid.isEmpty()) null else Dub(
                id = sid,
                name = o.optString("lanName"),
                code = o.optString("lanCode"),
                original = o.optBoolean("original"),
            )
        }
    }

    private fun collectRes(
        arr: org.json.JSONArray?,
        ep: Int,
        seen: MutableSet<String>,
        out: MutableList<StreamRes>,
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val itemEp = o.optInt("ep", 0)
            if (ep != 0 && itemEp != 0 && itemEp != ep) continue
            val res = o.optInt("resolution", 0)
            val codec = o.optString("codecName")
            if (res <= 0 || !seen.add("$res/$codec")) continue
            val src = o.optString("sourceUrl")
            if (src.isEmpty()) continue
            out.add(
                StreamRes(
                    resolution = res,
                    codec = codec,
                    // resource/v2 reports size in KB; some payloads use bytes.
                    size = (o.optString("size").toLongOrNull() ?: 0L)
                        .let { if (it in 1..10_000_000L) it * 1000L else it },
                    duration = o.optInt("duration", 0),
                    name = src.substringAfterLast('/'),
                    sourceUrl = src,
                    resourceId = o.optString("resourceId"),
                    postId = o.optString("postId"),
                )
            )
        }
    }

    /**
     * resource/v2 is paginated (20 per page) and ignores the `ep` query param, so a season has to be
     * walked page by page to find every codec/resolution for the requested episode. Page 1 tells us
     * the total count; the remaining pages are fetched in parallel.
     */
    suspend fun resources(subjectId: String, se: Int, ep: Int): List<StreamRes> =
        withContext(Dispatchers.IO) {
            val base = "/subject-api/resource/v2?host=api6.aoneroom.com&subjectId=$subjectId" +
                "&se=$se&ep=$ep&perPage=20"
            val out = ArrayList<StreamRes>()
            val seen = HashSet<String>()
            val first = call("$base&page=1").optJSONObject("data")
            collectRes(first?.optJSONArray("list"), ep, seen, out)
            if (se != 0 && ep != 0) {
                val total = first?.optJSONObject("pager")?.optInt("totalCount", 0) ?: 0
                val pages = ((total + 19) / 20).coerceIn(1, 8)
                if (pages > 1) {
                    val rest = coroutineScope {
                        (2..pages).map { p ->
                            async {
                                runCatching { call("$base&page=$p").optJSONObject("data") }.getOrNull()
                            }
                        }.awaitAll()
                    }
                    rest.forEach { collectRes(it?.optJSONArray("list"), ep, seen, out) }
                }
            }
            out.sortedWith(compareByDescending<StreamRes> { it.resolution }.thenBy { it.codec })
        }

    suspend fun sniff(sourceUrl: String, subjectId: String): String? =
        sniffInfo(sourceUrl, subjectId)?.url

    /** sniff/config returns the real signed CDN url plus the clip's own metadata. */
    suspend fun sniffInfo(sourceUrl: String, subjectId: String): SniffInfo? =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(sourceUrl, "UTF-8")
            val data = call("/sniff/config?host=api6.aoneroom.com&linkUrl=$encoded&subjectId=$subjectId")
            val d = data.optJSONObject("data") ?: return@withContext null
            val url = d.optString("downloadUrl").takeIf { it.isNotEmpty() } ?: return@withContext null
            val res = d.optJSONObject("resource")
            SniffInfo(
                url = url,
                name = d.optString("name"),
                duration = res?.optInt("duration", 0) ?: 0,
                size = res?.optString("size")?.toLongOrNull() ?: 0L,
            )
        }

    // ---------------------------------------------------------------- web (h5) playback

    /** Resolves the web slug of a subject, e.g. `lucifer-hindi-Aq2Bzbvyte1`. */
    suspend fun detailPath(subjectId: String): String = withContext(Dispatchers.IO) {
        slugCache[subjectId]?.let { return@withContext it }
        ensureToken()
        val data = call("/subject-api/get?subjectId=$subjectId")
        val url = data.optJSONObject("data")?.optString("detailUrl").orEmpty()
        val slug = url.trimEnd('/').substringAfterLast('/')
        if (slug.isNotEmpty()) slugCache[subjectId] = slug
        slug
    }

    private fun h5Get(path: String, slug: String): JSONObject {
        val req = Request.Builder()
            .url(H5_BASE + path)
            .header("User-Agent", WEB_UA)
            .header("Accept", "application/json")
            .header("Origin", WEB_ORIGIN)
            .header("Referer", webReferer(slug))
            .header("X-Source", "web")
            .header("X-Vip-Restrict", "false")
            .header("X-Client-Info", JSONObject().put("timezone", java.util.TimeZone.getDefault().id).toString())
            .build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
        }
    }

    // ---------------------------------------------------------------- catalog (web BFF)

    /** Unsigned h5 catalog call. A plain site referer is enough for these endpoints. */
    private fun h5Catalog(path: String, referer: String = "$WEB_ORIGIN/"): JSONObject {
        val req = Request.Builder()
            .url(H5_BASE + path)
            .header("User-Agent", WEB_UA)
            .header("Accept", "application/json")
            .header("Origin", WEB_ORIGIN)
            .header("Referer", referer)
            .header("X-Source", "web")
            .header("X-Vip-Restrict", "false")
            .header("X-Client-Info", JSONObject().put("timezone", java.util.TimeZone.getDefault().id).toString())
            .build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
        }
    }

    /** Genre chips exactly as the stock app shows them (Action, Adventure, …). */
    suspend fun genres(): List<String> = withContext(Dispatchers.IO) {
        ensureToken()
        val data = runCatching { call("/subject-api/filter-items?host=api6.aoneroom.com") }
            .getOrNull()?.optJSONObject("data") ?: return@withContext emptyList()
        val types = data.optJSONArray("typeList") ?: return@withContext emptyList()
        for (i in 0 until types.length()) {
            val items = types.optJSONObject(i)?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val f = items.optJSONObject(j) ?: continue
                if (f.optString("filterType") != "genre") continue
                val vals = f.optJSONArray("filterVals") ?: continue
                val out = ArrayList<String>()
                for (k in 0 until vals.length()) {
                    val v = vals.optString(k)
                    if (v.isNotEmpty() && v != "All") out.add(v)
                }
                if (out.isNotEmpty()) return@withContext out
            }
        }
        emptyList()
    }

    /** Titles for one genre chip, from the web filter endpoint. */
    suspend fun genreSubjects(genre: String, page: Int = 1): List<Subject> =
        withContext(Dispatchers.IO) {
            val path = "/filter?subjectType=0&genre=" + URLEncoder.encode(genre, "UTF-8") +
                "&page=$page&perPage=30"
            val data = runCatching { h5Catalog(path) }.getOrNull()?.optJSONObject("data")
                ?: return@withContext emptyList()
            val holder = data.optJSONObject("subjectList") ?: return@withContext emptyList()
            val arr = holder.optJSONArray("items") ?: return@withContext emptyList()
            val out = ArrayList<Subject>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sub = o.optJSONObject("subject") ?: o
                if (sub.optString("subjectId").isNotEmpty()) out.add(subjectOf(sub))
            }
            out
        }

    /** "You may also like" strip under the player (h5 recommendation endpoint). */
    suspend fun recommendations(subjectId: String): List<Subject> = withContext(Dispatchers.IO) {
        val slug = runCatching { detailPath(subjectId) }.getOrNull().orEmpty()
        val path = "/subject/detail-rec?subjectId=$subjectId" +
            if (slug.isNotEmpty()) "&detailPath=" + URLEncoder.encode(slug, "UTF-8") else ""
        val data = runCatching { h5Catalog(path) }.getOrNull()?.optJSONObject("data")
            ?: return@withContext emptyList()
        val arr = data.optJSONArray("items") ?: return@withContext emptyList()
        val out = ArrayList<Subject>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sub = o.optJSONObject("subject") ?: o
            if (sub.optString("subjectId").isNotEmpty()) out.add(subjectOf(sub))
        }
        out
    }

    /** Live search-as-you-type suggestions (words), same endpoint the stock app uses. */
    suspend fun suggestWords(keyword: String): List<String> = withContext(Dispatchers.IO) {
        if (keyword.trim().length < 2) return@withContext emptyList()
        ensureToken()
        val q = URLEncoder.encode(keyword.trim(), "UTF-8")
        val data = runCatching { call("/subject-api/search-suggest?host=api6.aoneroom.com&keyword=$q") }
            .getOrNull()?.optJSONObject("data") ?: return@withContext emptyList()
        val arr = data.optJSONArray("items") ?: return@withContext emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val w = o.optString("word")
            if (w.isNotEmpty() && out.none { it.equals(w, true) }) out.add(w)
            if (out.size >= 10) break
        }
        out
    }

    /**
     * Playable streams for a movie or one episode, straight from the web BFF.
     *
     * Movies are addressed as `se=0&ep=0`; series episodes are 1-based. The catalog is not
     * consistent, so both shapes are tried.
     */
    suspend fun h5Play(subjectId: String, se: Int, ep: Int): List<StreamRes> = withContext(Dispatchers.IO) {
        val slug = runCatching { detailPath(subjectId) }.getOrNull().orEmpty()
        if (slug.isEmpty()) return@withContext emptyList()

        fun attempt(aSe: Int, aEp: Int): List<StreamRes> {
            val query = "subjectId=$subjectId&se=$aSe&ep=$aEp" +
                "&detailPath=" + URLEncoder.encode(slug, "UTF-8") + "&streamSignType=1"
            val data = runCatching { h5Get("/subject/play?$query", slug) }.getOrNull()
                ?: return emptyList()
            val arr = data.optJSONObject("data")?.optJSONArray("streams") ?: return emptyList()
            val out = ArrayList<StreamRes>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isEmpty()) continue
                out.add(
                    StreamRes(
                        resolution = o.optString("resolutions").toIntOrNull() ?: 0,
                        codec = o.optString("codecName"),
                        size = o.optString("size").toLongOrNull() ?: 0L,
                        duration = o.optInt("duration", 0),
                        name = "${o.optString("resolutions")}p ${o.optString("codecName")}".trim(),
                        url = url,
                        referer = webReferer(slug),
                        sourceUrl = url,
                    )
                )
            }
            return out.sortedWith(
                compareByDescending<StreamRes> { it.resolution }.thenBy { if (isH264(it.codec)) 0 else 1 }
            )
        }

        val wanted = attempt(se, ep)
        if (wanted.isNotEmpty()) return@withContext wanted
        val other = if (se == 0 && ep == 0) attempt(1, 1) else attempt(0, 0)
        other
    }

    private fun isH264(codec: String): Boolean {
        val c = codec.lowercase()
        return c == "h264" || c == "avc" || c == "avc1"
    }

    /** Total size of a media file via a 1-byte range request; null when the server won't say. */
    suspend fun probeSize(url: String, referer: String? = null): Long? = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", if (referer != null) WEB_UA else PLAYER_UA)
            .header("Range", "bytes=0-0")
        referer?.let { builder.header("Referer", it) }
        runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                val range = resp.header("Content-Range")
                val total = range?.substringAfterLast('/')?.toLongOrNull()
                when {
                    total != null && total > 0 -> total
                    resp.isSuccessful || resp.code == 206 -> resp.body?.contentLength()?.takeIf { it > 0 }
                    else -> null
                }
            }
        }.getOrNull()
    }

    /** Anything under this is the placeholder/promo clip, never a real movie or episode. */
    const val MIN_REAL_BYTES = 5_000_000L

    /** Real stream for the requested resolution (falls back to the best available). */
    suspend fun stream(subjectId: String, se: Int, ep: Int, resolution: Int? = null): StreamRes? {
        val list = h5Play(subjectId, se, ep)
        if (list.isEmpty()) return null
        return list.firstOrNull { resolution != null && it.resolution == resolution } ?: list.first()
    }
}
