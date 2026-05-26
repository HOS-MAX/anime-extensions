package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Anime3rb : ParsedAnimeHttpSource() {

    override val name = "Anime3rb"

    override val baseUrl = "https://anime3rb.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val context: Context = Injekt.get()

    companion object {
        private var savedCookies: String = ""
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", "$baseUrl/")
    }

    // ========== الرئيسية (Popular / Latest) ==========

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/", headers)

    override fun popularAnimeSelector() = "h2:contains(الأنميات المثبتة)")
        .firstOrNull()?.parent()?.parent()?.parent()
        ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
        .toString()

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = cleanTitleText(element.select("h3.title-name").text())
            setUrlWithoutDomain(element.attr("href"))
            thumbnail_url = element.select("img").attr("src")
        }
    }

    override fun popularAnimeNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/", headers)

    override fun latestUpdatesSelector() = "#videos a.video-card"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    // ========== البحث (Search) ==========

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): okhttp3.Request {
        // نستخدم آليتك الذكية لجلب الكومبوننت والتوكن من الصفحة الأساسية لتشغيل الـ Livewire بنجاح
        val client = client.newCall(GET(baseUrl, headers)).execute()
        val doc = Jsoup.parse(client.body.string())
        val scriptTag = doc.selectFirst("script[src*=livewire.min.js]")
        val csrfToken = scriptTag?.attr("data-csrf") ?: ""
        val form = doc.selectFirst("form[wire:id]")
        val snapshotRaw = form?.attr("wire:snapshot") ?: ""
        val snapshotStr = org.jsoup.parser.Parser.unescapeEntities(snapshotRaw, true)

        val livewireHeaders = headersBuilder()
            .add("Accept", "*/*")
            .add("Content-Type", "application/json")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/")
            .add("Cookie", savedCookies)
            .build()

        val jsonPayload = """
            {
                "_token": "$csrfToken",
                "components": [
                    {
                        "snapshot": ${Json.parseToJsonElement(snapshotStr)},
                        "updates": {"query": "$query"},
                        "calls": []
                    }
                ]
            }
        """.trimIndent()

        val body = jsonPayload.toRequestBody("application/json".toMediaType())
        return POST("$baseUrl/livewire/update", livewireHeaders, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseText = response.body.string()
        val json = Json.parseToJsonElement(responseText).jsonObject
        val components = json["components"]?.jsonArray ?: return AnimesPage(emptyList(), false)
        val effects = components.firstOrNull()?.jsonObject?.get("effects")?.jsonObject ?: return AnimesPage(emptyList(), false)
        val htmlContent = effects["html"]?.jsonPrimitive?.content ?: return AnimesPage(emptyList(), false)

        val doc = Jsoup.parse(htmlContent)
        val animeList = doc.select("a.simple-title-card").map { item ->
            SAnime.create().apply {
                title = cleanTitleText(item.selectFirst("h4")?.text() ?: "")
                setUrlWithoutDomain(item.attr("href"))
                thumbnail_url = item.selectFirst("img")?.attr("src")
            }
        }
        return AnimesPage(animeList, false)
    }

    override fun searchAnimeSelector() = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    // ========== تفاصيل الأنمي (Anime Details) ==========

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            val rawTitle = document.selectFirst("h1")?.text() ?: ""
            title = cleanTitleText(rawTitle).replace(Regex("الحلقة \\d+"), "").trim()
            thumbnail_url = document.selectFirst("img[alt*='بوستر']")?.attr("src")
            genre = document.select("div.genres a").joinToString(", ") { it.text() }
            description = document.select("div.py-4.flex.flex-col.gap-2 p, p.synopsis").joinToString("\n") { it.text().trim() }
        }
    }

    // ========== الحلقات (Episodes) ==========

    override fun episodeListSelector() = ".video-list a, .episodes-list a"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            val videoData = element.selectFirst(".video-data")
            val epText = cleanTitleText(videoData?.selectFirst("span")?.text() ?: element.text())
            val epNum = epText.filter { it.isDigit() }.toFloatOrNull() ?: 1f
            
            setUrlWithoutDomain(element.attr("href"))
            name = epText
            episode_number = epNum
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.body.string())
        return doc.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    // ========== جلب الروابط مباشرة من سيرفر التحميل السريع (Video Extractor) ==========

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        val rawLinks = runOnUiThread { hijackAndExtractRawDirect(url) }

        return rawLinks.map { (src, label) ->
            Video(src, "Anime3rb - $label", src, headers = headersBuilder().add("Referer", "https://video.vid3rb.com/").build())
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ========== WebView Interceptor لصفحة التحميل المباشر الأصلي ==========

    private fun hijackAndExtractRawDirect(url: String): List<Pair<String, String>> {
        return suspendCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(context)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = USER_AGENT
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                val extractedRaw = mutableListOf<Pair<String, String>>()
                var isDone = false

                fun finish() {
                    if (isDone) return
                    isDone = true
                    try { webView.destroy() } catch (_: Exception) {}
                    cont.resume(extractedRaw.distinctBy { it.first })
                }

                // إلغاء المهلة الطويلة لإنهاء العملية بسرعة فور التقاط الرابط السريع
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 20000)

                webView.webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) = h!!.proceed()

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                        // يتم اقتناص ملف الـ /sources الخاص بسيرفر التحميل عالي السرعة (Speed=500) مباشرة وتجاهل الـ Player البطيء
                        if (reqUrl.contains("/sources") && reqUrl.contains("cf_token=")) {
                            Thread {
                                try {
                                    val connection = URL(reqUrl).openConnection() as HttpURLConnection
                                    connection.requestMethod = "GET"
                                    request.requestHeaders?.forEach { (k, v) ->
                                        if (!k.equals("Accept-Encoding", true)) connection.setRequestProperty(k, v)
                                    }
                                    CookieManager.getInstance().getCookie(reqUrl)?.let { connection.setRequestProperty("Cookie", it) }

                                    val responseBytes = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream).readBytes()
                                    val jsonString = String(responseBytes, Charsets.UTF_8)

                                    val json = Json.parseToJsonElement(jsonString).jsonArray
                                    json.forEach { item ->
                                        val src = item.jsonObject["src"]?.jsonPrimitive?.content ?: item.jsonObject["file"]?.jsonPrimitive?.content
                                        val label = item.jsonObject["label"]?.jsonPrimitive?.content ?: "Direct High Speed"
                                        if (!src.isNullOrBlank()) {
                                            extractedRaw.add(src to label)
                                        }
                                    }

                                    if (extractedRaw.isNotEmpty()) {
                                        Handler(Looper.getMainLooper()).post { finish() }
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
    }

    // ========== أدوات مساعدة ==========

    private fun <T> runOnUiThread(block: suspend () -> T): T {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("Cannot run architecture network block directly on Main Thread")
        } else {
            var result: T? = null
            var error: Throwable? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    try { result = block() } catch (e: Throwable) { error = e } finally { latch.countDown() }
                }
            }
            latch.await()
            error?.let { throw it }
            result!!
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
