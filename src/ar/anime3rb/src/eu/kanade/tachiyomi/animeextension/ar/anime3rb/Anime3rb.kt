package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray

class Anime3rb : ParsedAnimeHttpSource() {

    override val name = "Anime3rb"
    override val baseUrl = "https://anime3rb.com"
    override val lang = "ar"
    override val supportsLatest = true

    // Uses Aniyomi's native browser client to bypass Cloudflare automatically
    override val client: OkHttpClient = network.cloudflareClient

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "$baseUrl/")

    // ============================== POPULAR / HOME ANIME ==============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime-list?page=$page", headers)

    override fun popularAnimeSelector(): String = "div.custom-col, div.grid a.video-card, a.simple-title-card"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val anchor = if (element.tagName() == "a") element else element.selectFirst("a")!!
        anime.setUrlWithoutDomain(anchor.attr("href"))
        anime.title = cleanTitleText(element.select("h1.title, h3.title-name, h4").text())
        anime.thumbnail_url = element.select("img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item a[rel=next]"

    // ============================== LATEST ANIME ==============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest-episodes?page=$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== ANIME SEARCH ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/anime-list?search=$query&page=$page", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== ANIME DETAILS ==============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val rawTitle = document.selectFirst("h1")?.text() ?: ""
        anime.title = cleanTitleText(rawTitle)
            .replace(Regex("الحلقة \\d+"), "")
            .replace("( مسلسل )", "")
            .replace("( فيلم )", "")
            .trim()

        anime.thumbnail_url = document.selectFirst("img[alt*=بوستر]")?.attr("src")
        anime.description = document.select("div.py-4.flex.flex-col.gap-2 p, p.synopsis, meta[name=description]").joinToString("\n") { it.text().trim() }.trim()
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ============================== EPISODES ==============================
    override fun episodeListSelector(): String = "div.video-list a, .episodes-list a, div.grid a[href*=/episode/]"

    override fun episodeListFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        
        val videoData = element.selectFirst(".video-data")
        val epText = cleanTitleText(videoData?.selectFirst("span")?.text() ?: videoData?.children()?.getOrNull(0)?.text() ?: element.text())
        val epName = cleanTitleText(videoData?.selectFirst("p")?.text() ?: videoData?.children()?.getOrNull(1)?.text() ?: "")

        episode.name = if (epName.isNotBlank()) epName else epText
        val epNumStr = Regex("[^0-9]").replace(epText, "")
        episode.episode_number = epNumStr.toFloatOrNull() ?: 1f
        return episode
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    // ============================== EXTRACTOR PIPELINE ==============================
    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = response.asJsoup()
        
        // Ported from your Cloudstream Logic: Find the direct player iframe element on the page
        val iframeUrl = document.selectFirst("iframe[src*=/player/]")?.attr("src") 
            ?: document.selectFirst("iframe")?.attr("src") 
            ?: ""

        if (iframeUrl.isBlank()) return emptyList()

        val videoHeaders = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", baseUrl)
            .add("Accept", "*/*")
            .build()

        try {
            // Directly fetch the player content via OkHttp
            val playerResponse = client.newCall(GET(iframeUrl, videoHeaders)).execute()
            if (playerResponse.isSuccessful) {
                val playerHtml = playerResponse.body?.string() ?: ""
                
                // Matches your exact extraction regex string: var video_sources = [...]
                val jsonPattern = """var\s+video_sources\s*=\s*(\[[^;]+]);""".toRegex()
                val jsonMatch = jsonPattern.find(playerHtml)

                if (jsonMatch != null) {
                    val jsonStr = jsonMatch.groupValues[1]
                    val jsonArray = JSONArray(jsonStr)
                    
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val src = if (item.has("src")) item.getString("src") else if (item.has("file")) item.getString("file") else ""
                        val label = if (item.has("label")) item.getString("label") else "Default"
                        
                        if (src.isNotBlank()) {
                            if (src.contains(".m3u8")) {
                                try {
                                    val playlistResponse = client.newCall(GET(src, videoHeaders)).execute()
                                    if (playlistResponse.isSuccessful) {
                                        val body = playlistResponse.body?.string() ?: ""
                                        if (body.contains("#EXT-X-STREAM-INF")) {
                                            val lines = body.split("\n")
                                            for (j in lines.indices) {
                                                if (lines[j].contains("#EXT-X-STREAM-INF")) {
                                                    val resMatch = Regex("""RESOLUTION=(\d+x\d+)""").find(lines[j])
                                                    val height = resMatch?.groupValues?.get(1)?.split("x")?.get(1) ?: label
                                                    
                                                    var videoUrl = lines[j + 1].trim()
                                                    if (!videoUrl.startsWith("http")) {
                                                        val baseUrlPath = src.substring(0, src.lastIndexOf("/") + 1)
                                                        videoUrl = baseUrlPath + videoUrl
                                                    }
                                                    videoList.add(Video(videoUrl, "${height}p - Anime3rb", videoUrl, headers = videoHeaders))
                                                }
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                            
                            if (videoList.none { it.videoUrl == src }) {
                                videoList.add(Video(src, "$label - Anime3rb", src, headers = videoHeaders))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return videoList
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException("Not used")
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Not used")
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("بترجمة.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
