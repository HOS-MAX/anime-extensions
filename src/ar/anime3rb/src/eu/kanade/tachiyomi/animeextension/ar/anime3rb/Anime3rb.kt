package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Anime3rb : ParsedAnimeHttpSource() {

    override val name = "Anime3rb"
    override val baseUrl = "https://anime3rb.com"
    override val lang = "ar"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", baseUrl)

    // ============================== POPULAR ANIME ==============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime-list?page=$page", headers)

    override fun popularAnimeSelector(): String = "div.custom-col"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val anchor = element.selectFirst("a")
        anime.setUrlWithoutDomain(anchor.attr("href"))
        anime.title = element.selectFirst("h1.title")?.text() ?: ""
        anime.thumbnail_url = element.selectFirst("img")?.attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item a[rel=next]"

    // ============================== LATEST ANIME ==============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest-episodes?page=$page", headers)

    override fun latestUpdatesSelector(): String = "div.custom-col"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val anchor = element.selectFirst("a")
        val href = anchor.attr("href")
        val cleanHref = if (href.contains("/episode/")) href.replace("/episode/", "/anime/") else href
        anime.setUrlWithoutDomain(cleanHref)
        anime.title = element.selectFirst("h1.title")?.text()?.replace(Regex("الحلقة \\d+"), "")?.trim() ?: ""
        anime.thumbnail_url = element.selectFirst("img")?.attr("src")
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "li.page-item a[rel=next]"

    // ============================== ANIME SEARCH ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search?q=$query&page=$page", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== ANIME DETAILS ==============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1")?.text() ?: ""
        anime.thumbnail_url = document.selectFirst("img[alt*=بوستر]")?.attr("src")
        anime.description = document.select("div.py-4.flex.flex-col.gap-2 p, p.synopsis").joinToString("\n") { it.text() }.trim()
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ============================== EPISODES ==============================
    override fun episodeListSelector(): String = "div.video-list a, .episodes-list a"

    override fun episodeListFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        
        val titleText = element.text()
        episode.name = titleText
        
        val epNumStr = Regex("[^0-9]").replace(titleText, "")
        episode.episode_number = epNumStr.toFloatOrNull() ?: 1f
        return episode
    }

    // ============================== VIDEO EXTRACTION ==============================
    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val currentUrl = response.request.url.toString()
        
        // Execute the webview script synchronously on the background context 
        val rawMasterStreamUrl = runBlocking {
            hijackAndExtractRaw(currentUrl)
        }

        if (rawMasterStreamUrl.isNotEmpty()) {
            val videoHeaders = Headers.Builder()
                .add("User-Agent", USER_AGENT)
                .add("Referer", "https://video.vid3rb.com/")
                .add("Accept", "*/*")
                .build()

            // Regular expression to break down alternative layouts inside master playlist streams
            if (rawMasterStreamUrl.contains(".m3u8")) {
                try {
                    // Extract track playlists recursively using standard HLS playlists
                    val playlistResponse = client.newCall(GET(rawMasterStreamUrl, videoHeaders)).execute()
                    if (playlistResponse.isSuccessful) {
                        val body = playlistResponse.body?.string() ?: ""
                        
                        if (body.contains("#EXT-X-STREAM-INF")) {
                            // Split multi-qualities (1080p, 720p, 480p, 360p) manually into Aniyomi track objects
                            var lines = body.split("\n")
                            for (i in lines.indices) {
                                if (lines[i].contains("#EXT-X-STREAM-INF")) {
                                    val resolutionMatch = Regex("""RESOLUTION=(\d+x\d+)""").find(lines[i])
                                    val height = resolutionMatch?.groupValues?.get(1)?.split("x")?.get(1) ?: "Video"
                                    
                                    var videoUrl = lines[i + 1].trim()
                                    if (!videoUrl.startsWith("http")) {
                                        val baseUrlPath = rawMasterStreamUrl.substring(0, rawMasterStreamUrl.lastIndexOf("/") + 1)
                                        videoUrl = baseUrlPath + videoUrl
                                    }
                                    videoList.add(Video(videoUrl, "${height}p - Anime3rb", videoUrl, headers = videoHeaders))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Fail gracefully
                }
            }
            
            // Safe fallback rule if it is a flat playlist link or direct media output stream
            if (videoList.isEmpty()) {
                videoList.add(Video(rawMasterStreamUrl, "Auto Quality", rawMasterStreamUrl, headers = videoHeaders))
            }
        }

        return videoList
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException("Not used")
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Not used")
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    // ============================== WEBVIEW INJECTOR LOOP ==============================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun hijackAndExtractRaw(url: String): String = withContext(Dispatchers.Main) {
        suspendCoroutine { continuation ->
            val context = eu.kanade.tachiyomi.App.getAppContext() ?: run {
                continuation.resume("")
                return@suspendCoroutine
            }

            val webView = WebView(context)
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = USER_AGENT

            CookieManager.getInstance().setAcceptCookie(true)

            var resolved = false
            val handler = Handler(Looper.getMainLooper())
            
            val timeoutRunnable = Runnable {
                if (!resolved) {
                    resolved = true
                    webView.destroy()
                    continuation.resume("")
                }
            }
            handler.postDelayed(timeoutRunnable, 15000)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, currentUrl: String?) {
                    super.onPageFinished(view, currentUrl)
                    
                    handler.postDelayed(object : Runnable {
                        override fun run() {
                            if (resolved) return
                            
                            webView.evaluateJavascript(
                                "(function() { " +
                                "   var videos = document.getElementsByTagName('video');" +
                                "   if(videos.length > 0 && videos[0].src) { return videos[0].src; }" +
                                "   var sources = document.getElementsByTagName('source');" +
                                "   if(sources.length > 0 && sources[0].src) { return sources[0].src; }" +
                                "   return ''; " +
                                "})();"
                            ) { src ->
                                val cleanSrc = src?.replace("\"", "") ?: ""
                                if (cleanSrc.isNotEmpty() && cleanSrc.contains("m3u8")) {
                                    resolved = true
                                    handler.removeCallbacks(timeoutRunnable)
                                    webView.destroy()
                                    continuation.resume(cleanSrc)
                                } else {
                                    if (!resolved) handler.postDelayed(this, 1000)
                                }
                            }
                        }
                    }, 2000)
                }
            }
            webView.loadUrl(url)
        }
    }
}
