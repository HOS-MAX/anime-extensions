package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import android.webkit.CookieManager
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Anime3rb :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anim3rb"

    override val baseUrl = "https://anime3rb.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // Dynamically retrieve stored System WebView Session Cookies synchronized by Aniyomi
    private fun getSystemCookies(url: String): String {
        return try {
            CookieManager.getInstance().getCookie(url) ?: ""
        } catch (_: Exception) { "" }
    }

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/", headers)

    override fun popularAnimeSelector() = "h3:contains(آخر الأنميات المضافة), h2:contains(الأنميات المثبتة)"

    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        
        document.select(".glide__slide:not(.glide__slide--clone) a.video-card, #videos a.video-card").forEach { el ->
            val anime = SAnime.create().apply {
                title = cleanTitleText(el.select("h3.title-name").text())
                thumbnail_url = el.select("img").attr("abs:src")
                setUrlWithoutDomain(el.attr("href"))
            }
            if (animeList.none { it.url == anime.url }) animeList.add(anime)
        }
        
        return AnimesPage(animeList, false)
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = popularAnimeRequest(page)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val mainDoc = response.asJsoup()
        val query = mainDoc.location().substringAfter("?query=", "")
        
        if (query.isBlank()) return popularAnimeParse(response)

        val scriptTag = mainDoc.selectFirst("script[src*=livewire.min.js]") ?: return AnimesPage(emptyList(), false)
        val csrfToken = scriptTag.attr("data-csrf")
        val form = mainDoc.selectFirst("form[wire:id]") ?: return AnimesPage(emptyList(), false)
        val snapshotStr = form.attr("wire:snapshot")

        val updateUrl = "$baseUrl/livewire/update"
        
        val payload = JSONObject().apply {
            put("_token", csrfToken)
            put("components", JSONArray().apply {
                put(JSONObject().apply {
                    put("snapshot", snapshotStr)
                    put("updates", JSONObject().apply { put("query", query) })
                    put("calls", JSONArray())
                })
            })
        }

        val searchHeaders = headersBuilder()
            .set("Accept", "*/*")
            .set("Content-Type", "application/json")
            .set("Origin", baseUrl)
            .build()

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val postRes = client.newCall(POST(updateUrl, searchHeaders, requestBody)).execute()
        
        if (postRes.code != 200) return AnimesPage(emptyList(), false)

        val responseJson = JSONObject(postRes.body.string())
        val components = responseJson.getJSONArray("components")
        val effects = components.getJSONObject(0).getJSONObject("effects")
        val htmlContent = effects.getString("html")

        val soupResults = Jsoup.parse(htmlContent)
        val animeList = soupResults.select("a.simple-title-card").map { item ->
            SAnime.create().apply {
                title = cleanTitleText(item.selectFirst("h4")?.text() ?: "")
                thumbnail_url = item.selectFirst("img")?.attr("abs:src")
                setUrlWithoutDomain(item.attr("href"))
            }
        }

        return AnimesPage(animeList, false)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val rawTitle = document.selectFirst("h1")?.text() ?: ""
        title = cleanTitleText(rawTitle).replace(TITLE_EP_REGEX, "").trim()
        thumbnail_url = document.selectFirst("img[alt*='بوستر']")?.attr("abs:src")
        description = document.select("div.py-4.flex.flex-col.gap-2 p, p.synopsis").joinToString("\n") { it.text().trim() }
        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = ".video-list a, .episodes-list a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val videoData = element.selectFirst(".video-data")
        val epText = cleanTitleText(videoData?.selectFirst("span")?.text() ?: videoData?.children()?.getOrNull(0)?.text() ?: "")
        val epNum = epText.filter { it.isDigit() }.toFloatOrNull()
        
        episode_number = epNum ?: 1F
        val epName = cleanTitleText(videoData?.selectFirst("p")?.text() ?: videoData?.children()?.getOrNull(1)?.text() ?: "")
        name = if (epName.isNotBlank()) epName else epText
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector()).map(::episodeFromElement)
        return if (episodes.size > 1 && (episodes.first().episode_number > episodes.last().episode_number)) {
            episodes.reversed()
        } else {
            episodes
        }
    }

    // ============================ Video Links (The Turnstile Fix) =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // 1. Identify dynamic iframe components containing the player tokens
        val playerElements = document.select("iframe[src*=/player/], iframe[src*=/sources], iframe[src*=vid3rb]")

        playerElements.forEach { iframe ->
            try {
                val playerUrl = iframe.attr("abs:src")
                val cookieStr = getSystemCookies(playerUrl) // Pull system verified tokens
                
                val playerHeaders = headersBuilder()
                    .set("Referer", "$baseUrl/")
                    .set("Cookie", cookieStr) // Explicitly forward clearance cookies
                    .build()
                
                val playerResponse = client.newCall(GET(playerUrl, playerHeaders)).execute()
                val playerHtml = playerResponse.body.string()

                // Strategy A: Evaluate internal JavaScript config maps
                videoList.addAll(extractSourcesFromScript(playerHtml))
                
                // Strategy B: Pull direct fallback backend schema if scripts are scrambled
                if (videoList.isEmpty() && playerUrl.contains("/player/")) {
                    val id = playerUrl.substringAfter("/player/").substringBefore("?")
                    val apiUrl = "$baseUrl/api/video/$id"
                    val apiResponse = client.newCall(GET(apiUrl, playerHeaders)).execute()
                    if (apiResponse.code == 200) {
                        videoList.addAll(extractSourcesFromScript(apiResponse.body.string()))
                    }
                }
            } catch (_: Exception) {}
        }

        // Strategy C: Scrape root body layouts directly
        if (videoList.isEmpty()) {
            videoList.addAll(extractSourcesFromScript(document.select("script").html()))
        }

        return videoList
    }

    private fun extractSourcesFromScript(html: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            // Updated regex pattern to intercept raw array streams safely
            val jsonRegex = """(?:var\s+video_sources\s*=\s*|sources:\s*)(\[[^;\]\n]+])""".toRegex()
            val match = jsonRegex.find(html)
            
            if (match != null) {
                val jsonArray = JSONArray(match.groupValues[1])
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val src = when {
                        item.has("src") -> item.getString("src")
                        item.has("file") -> item.getString("file")
                        else -> ""
                    }
                    val label = if (item.has("label")) item.getString("label") else "Direct Server"
                    
                    if (src.isNotBlank()) {
                        // Crucial fix: Attach the verified referer data so watch/download links stream natively
                        val videoHeaders = headersBuilder()
                            .set("Referer", "https://video.vid3rb.com/")
                            .set("Origin", "https://video.vid3rb.com")
                            .build()
                            
                        videos.add(Video(src, "Anim3rb - $label", src, videoHeaders))
                    }
                }
            }
        } catch (_: Exception) {}
        return videos
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue("720p")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_QUALITY_KEY, newValue as String).commit()
            }
        }
        screen.addPreference(qualityPref)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, "720p")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================= Utilities ==============================
    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("بترجمة.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        private val TITLE_EP_REGEX = Regex("الحلقة \\d+|( مسلسل )|( فيلم )")
    }
}
