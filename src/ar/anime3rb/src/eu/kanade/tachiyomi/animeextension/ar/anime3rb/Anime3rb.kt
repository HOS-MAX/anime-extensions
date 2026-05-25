package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder

class Anime3rb : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Anim3rbtest"

    override val baseUrl = "https://anime3rb.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val json: Json by lazy { Injekt.get() }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", Application.MODE_PRIVATE)
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val TITLE_EP_REGEX = Regex("الحلقة \\d+")
        private val NON_DIGITS = Regex("[^0-9]")
        
        private const val COOKIE_KEY = "anime3rb_cookie_v2"
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val cookieHeader = preferences.getString(COOKIE_KEY, "") ?: ""
            
            val builder = originalRequest.newBuilder()
                .header("User-Agent", USER_AGENT)
            
            if (cookieHeader.isNotBlank()) {
                builder.header("Cookie", cookieHeader)
            }
            chain.proceed(builder.build())
        }
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    // ============================== POPULAR / LATEST ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body.string())
        val animeList = doc.select("h2:contains(الأنميات المثبتة)").firstOrNull()
            ?.parent()?.parent()?.parent()
            ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
            ?.map { element ->
                SAnime.create().apply {
                    title = cleanTitleText(element.select("h3.title-name").text())
                    setUrlWithoutDomain(element.attr("href"))
                    thumbnail_url = element.select("img").attr("src")
                }
            } ?: emptyList()
        
        return AnimesPage(animeList, hasNextPage = false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body.string())
        val animeList = doc.select("#videos a.video-card").map { element ->
            SAnime.create().apply {
                title = cleanTitleText(element.select("h3.title-name").text())
                setUrlWithoutDomain(element.attr("href"))
                thumbnail_url = element.select("img").attr("src")
            }
        }
        return AnimesPage(animeList, hasNextPage = false)
    }

    // ============================== SEARCH (LIVEWIRE PORT) ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)

        // Step 1: Sync call to get token and Livewire component data
        val mainRequest = client.newCall(GET(baseUrl, headers)).execute()
        val mainDoc = Jsoup.parse(mainRequest.body.string())

        val scriptTag = mainDoc.selectFirst("script[src*=livewire.min.js]")
        val csrfToken = scriptTag?.attr("data-csrf") ?: throw Exception("CSRF Token not found")

        val form = mainDoc.selectFirst("form[wire:id]")
        val snapshotRaw = form?.attr("wire:snapshot") ?: throw Exception("Livewire snapshot not found")
        val snapshotStr = Jsoup.parse(snapshotRaw).text()

        // Step 2: Build Livewire update API Payload
        val updateUrl = "$baseUrl/livewire/update"
        val payload = """
            {
                "_token": "$csrfToken",
                "components": [
                    {
                        "snapshot": ${json.parseToJsonElement(snapshotStr)},
                        "updates": { "query": "$query" },
                        "calls": []
                    }
                ]
            }
        """.trimIndent()

        val body = payload.toRequestBody("application/json".toMediaType())
        return POST(updateUrl, headersBuilder().add("Accept", "*/*").build(), body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseBody = response.body.string()
        if (response.code != 200) return AnimesPage(emptyList(), false)

        return try {
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val components = jsonResponse["components"]?.jsonArray?.get(0)?.jsonObject ?: return AnimesPage(emptyList(), false)
            val effects = components["effects"]?.jsonObject ?: return AnimesPage(emptyList(), false)
            val htmlContent = effects["html"]?.jsonPrimitive?.content ?: return AnimesPage(emptyList(), false)

            val soupResults = Jsoup.parse(htmlContent)
            val animeList = soupResults.select("a.simple-title-card").map { item ->
                SAnime.create().apply {
                    val rawTitle = item.selectFirst("h4")?.text()?.trim() ?: ""
                    title = cleanTitleText(rawTitle)
                    setUrlWithoutDomain(item.attr("href"))
                    thumbnail_url = item.selectFirst("img")?.attr("src")
                }
            }
            AnimesPage(animeList, hasNextPage = false)
        } catch (e: Exception) {
            AnimesPage(emptyList(), false)
        }
    }

    // ============================== DETAILS & EPISODES ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(response.body.string())
        return SAnime.create().apply {
            var rawTitle = doc.selectFirst("h1")?.text() ?: ""
            rawTitle = cleanTitleText(rawTitle)
            title = TITLE_EP_REGEX.replace(rawTitle, "").replace("( مسلسل )", "").replace("( فيلم )", "").trim()
            
            thumbnail_url = doc.selectFirst("img[alt*='بوستر']")?.attr("src") ?: ""
            
            var descriptionText = doc.select("div.py-4.flex.flex-col.gap-2 p, p.synopsis").joinToString("\n") { it.text().trim() }
            if (descriptionText.isBlank()) {
                descriptionText = doc.select("meta[name=description]").attr("content").trim()
            }
            description = descriptionText
            status = SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.body.string())
        val elements = doc.select(".video-list a, .episodes-list a")
        
        var episodes = elements.map { element ->
            SEpisode.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                val videoData = element.selectFirst(".video-data")
                val epText = cleanTitleText(videoData?.selectFirst("span")?.text() ?: videoData?.children()?.getOrNull(0)?.text() ?: "")
                val epName = cleanTitleText(videoData?.selectFirst("p")?.text() ?: videoData?.children()?.getOrNull(1)?.text() ?: "")
                
                name = epName.ifBlank { epText }
                episode_number = NON_DIGITS.replace(epText, "").toFloatOrNull() ?: 1f
            }
        }

        if (episodes.size > 1) {
            val firstEpNum = episodes.first().episode_number
            val lastEpNum = episodes.last().episode_number
            if (firstEpNum > lastEpNum && lastEpNum != 0f) {
                episodes = episodes.reversed()
            }
        }
        return episodes
    }

    // ============================== VIDEO EXTRACTOR ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val videoList = mutableListOf<Video>()

        // Scraping video sources directly from the script context
        val jsonPattern = """var\s+video_sources\s*=\s*(\[[^;]+]);""".toRegex()
        val jsonMatch = jsonPattern.find(html)

        if (jsonMatch != null) {
            val jsonStr = jsonMatch.groupValues[1]
            try {
                val linksFromJson = json.parseToJsonElement(jsonStr).jsonArray
                linksFromJson.forEach { item ->
                    val obj = item.jsonObject
                    val src = obj["src"]?.jsonPrimitive?.content ?: obj["file"]?.jsonPrimitive?.content ?: ""
                    val label = obj["label"]?.jsonPrimitive?.content ?: "Default"
                    if (src.isNotBlank()) {
                        val videoHeaders = headersBuilder()
                            .add("Referer", "https://video.vid3rb.com/")
                            .build()
                        videoList.add(Video(src, "$name - $label", src, headers = videoHeaders))
                    }
                }
            } catch (_: Exception) {}
        }

        // Resolving player endpoint parameters if present via /sources
        if (html.contains("/sources") && html.contains("cf_token=")) {
            val sourceUrlPattern = """["'](https?://[^"']+/sources\?cf_token=[^"']+)["']""".toRegex()
            val match = sourceUrlPattern.find(html)
            match?.groupValues?.get(1)?.let { rawSourceUrl ->
                try {
                    val sourceUrl = URLDecoder.decode(rawSourceUrl, "UTF-8")
                    val sourceResponse = client.newCall(GET(sourceUrl, headers)).execute()
                    val linksFromJson = json.parseToJsonElement(sourceResponse.body.string()).jsonArray
                    linksFromJson.forEach { item ->
                        val obj = item.jsonObject
                        val src = obj["src"]?.jsonPrimitive?.content ?: obj["file"]?.jsonPrimitive?.content ?: ""
                        val label = obj["label"]?.jsonPrimitive?.content ?: "Default"
                        if (src.isNotBlank()) {
                            videoList.add(Video(src, "$name - $label", src, headers = headers))
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return videoList
    }

    // ============================== UTILS & PREFERENCES ==============================

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("بترجمة.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context
        val cookieEditPref = EditTextPreference(ctx).apply {
            key = COOKIE_KEY
            title = "Manual Cloudflare Clearance Cookie"
            summary = "If content fails to load, inspect and paste your 'cf_clearance' or session cookies here."
            dialogTitle = "Cloudflare Cookie"
        }
        screen.addPreference(cookieEditPref)
    }
}
