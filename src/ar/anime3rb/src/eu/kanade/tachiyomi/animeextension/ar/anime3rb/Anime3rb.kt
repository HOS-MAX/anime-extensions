package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.SslErrorHandler
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.Headers
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.json.JSONArray
import org.json.JSONObject

class Anime3rb : ParsedAnimeHttpSource() {

    override val name = "Anime3rb"
    override val baseUrl = "https://anime3rb.com"
    override val lang = "ar"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client

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
            .replace("( film )", "")
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
        val currentUrl = response.request.url.toString()

        val rawLinks = runBlocking {
            hijackAndExtractRaw(currentUrl)
        }

        val videoHeaders = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", "https://video.vid3rb.com/")
            .add("Accept", "*/*")
            .build()

        rawLinks.forEach { (src, label) ->
            if (src.contains(".m3u8")) {
                try {
                    val playlistResponse = client.newCall(GET(src, videoHeaders)).execute()
                    if (playlistResponse.isSuccessful) {
                        val body = playlistResponse.body?.string() ?: ""
                        if (body.contains("#EXT-X-STREAM-INF")) {
                            val lines = body.split("\n")
                            for (i in lines.indices) {
                                if (lines[i].contains("#EXT-X-STREAM-INF")) {
                                    val resMatch = Regex("""RESOLUTION=(\d+x\d+)""").find(lines[i])
                                    val height = resMatch?.groupValues?.get(1)?.split("x")?.get(1) ?: label
                                    
                                    var videoUrl = lines[i + 1].trim()
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

        return videoList
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException("Not used")
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Not used")
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    // ============================== HEADLESS INTERCEPTOR ==============================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun hijackAndExtractRaw(
        url: String,
        timeoutMs: Long = 30_000L
    ): List<Pair<String, String>> = withContext(Dispatchers.Main) {
        suspendCoroutine { cont ->
            // Uses standard parameter loop context natively supported across all standard Android versions
            val webView = WebView(runBlocking(Dispatchers.Main) { android.app.Activity().applicationContext ?: WebView(android.app.Activity()).context })
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = USER_AGENT
                blockNetworkImage = false
                mediaPlaybackRequiresUserGesture = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            val extractedRaw = mutableListOf<Pair<String, String>>()
            var isDone = false
            val handler = Handler(Looper.getMainLooper())

            fun finish() {
                if (isDone) return
                isDone = true
                try {
                    handler.removeCallbacksAndMessages(null)
                    webView.destroy()
                } catch (_: Exception) {}
                cont.resume(extractedRaw.distinctBy { it.first })
            }

            handler.postDelayed({ finish() }, timeoutMs)

            webView.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) = h!!.proceed()

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    if (request == null || request.url == null) return super.shouldInterceptRequest(view, request)
                    val reqUrl = request.url.toString()

                    if (reqUrl.contains("/player/") && !reqUrl.contains("cf_token=")) {
                        Thread {
                            try {
                                val connection = URL(reqUrl).openConnection() as HttpURLConnection
                                connection.requestMethod = "GET"
                                request.requestHeaders?.forEach { (k, v) ->
                                    if (!k.equals("Accept-Encoding", true)) connection.setRequestProperty(k, v)
                                }
                                CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                                connection.setRequestProperty("Referer", url)

                                val playerHtml = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
                                val jsonPattern = """var\s+video_sources\s*=\s*(\[[^;]+]);""".toRegex()
                                val jsonMatch = jsonPattern.find(playerHtml)

                                if (jsonMatch != null) {
                                    val jsonStr = jsonMatch.groupValues[1]
                                    val jsonArray = JSONArray(jsonStr)
                                    for (i in 0 until jsonArray.length()) {
                                        val item = jsonArray.getJSONObject(i)
                                        val src = if (item.has("src")) item.getString("src") else if (item.has("file")) item.getString("file") else ""
                                        val label = if (item.has("label")) item.getString("label") else "Default"
                                        if (src.isNotBlank()) extractedRaw.add(src to label)
                                    }

                                    if (extractedRaw.isNotEmpty()) {
                                        handler.post { finish() }
                                    }
                                }
                            } catch (_: Exception) {}
                        }.start()
                        return super.shouldInterceptRequest(view, request)
                    }

                    if (reqUrl.contains("/sources") && reqUrl.contains("cf_token=")) {
                        Thread {
                            try {
                                val connection = URL(reqUrl).openConnection() as HttpURLConnection
                                connection.requestMethod = "GET"
                                request.requestHeaders?.forEach { (k, v) ->
                                    if (!k.equals("Accept-Encoding", true)) connection.setRequestProperty(k, v)
                                }
                                CookieManager.getInstance().getCookie(reqUrl)?.let { connection.setRequestProperty("Cookie", it) }

                                val jsonString = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
                                val jsonArray = JSONArray(jsonString)
                                for (i in 0 until jsonArray.length()) {
                                    val item = jsonArray.getJSONObject(i)
                                    val src = if (item.has("src")) item.getString("src") else if (item.has("file")) item.getString("file") else ""
                                    val label = if (item.has("label")) item.getString("label") else "Default"
                                    if (src.isNotBlank()) extractedRaw.add(src to label)
                                }

                                if (extractedRaw.isNotEmpty()) {
                                    handler.post { finish() }
                                }
                            } catch (_: Exception) {}
                        }.start()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView.loadUrl(url)
        }
    }

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("بترجمة.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
