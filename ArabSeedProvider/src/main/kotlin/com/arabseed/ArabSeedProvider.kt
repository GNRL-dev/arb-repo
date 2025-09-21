package com.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
//import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import android.net.Uri
import org.json.JSONObject

class ArabSeed : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://a.asd.homes"
    override var name = "ArabSeed"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // --- Parse search items ---
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href") ?: return null
        val title = selectFirst("h3")?.text() ?: this.attr("title") ?: return null

      val posterUrl = selectFirst("img")?.attr("data-src")
    ?: selectFirst(".post__image img")?.attr("src")
    

        println("ArabSeedProvider: Parsed search item -> title=$title, posterUrl=$posterUrl")

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // --- Home categories ---
    override val mainPage = mainPageOf(
        "$mainUrl/main0/" to "الرئيسية",
        "$mainUrl/category/foreign-movies-6/" to "أفلام اجنبي",
        "$mainUrl/category/asian-movies/" to "افلام آسيوية",
        "$mainUrl/category/arabic-movies-5/" to "افلام عربي",
        "$mainUrl/category/foreign-series-2/" to "مسلسلات اجنبي",
        "$mainUrl/category/arabic-series-2/" to "مسلسلات عربي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%83%d9%88%d8%b1%d9%8a%d9%87/" to "مسلسلات كورية",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%8a%d9%85%d9%8a%d8%b4%d9%86/" to "أفلام انيميشن",
        "$mainUrl/category/cartoon-series/" to " أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val items = doc.select("a.movie__block").mapNotNull { it.toSearchResponse() }
        val hasNext = doc.select(".page-numbers a").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    // --- Search ---
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/find/?word=${query.replace(" ", "+")}&type="
        val doc = app.get(url).document
        return doc.select("a.movie__block").mapNotNull { it.toSearchResponse() }
    }

    // --- Load details ---
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("title")?.text().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("div.post__story p")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val year = doc.selectFirst(".info__area li:contains(سنة العرض) a")?.text()?.toIntOrNull()
        val genres = doc.select(".info__area li:contains(نوع العرض) a").map { it.text() }


        val episodes = doc.select("ul.episodes__list li a").map {
            val rawName = it.selectFirst(".epi__num")?.text()?.trim() ?: "Episode"
            val cleanName = rawName.replace(Regex("(?<=\\D)(?=\\d)"), " ")

            newEpisode(it.attr("href")) {
                this.name = cleanName
            }
        }.ifEmpty {
            doc.select("a.watch__btn").map {
                newEpisode(it.attr("href")) {
                    this.name = "مشاهدة الان"
                }
            }
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        }
    }

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl,
        "Accept-Language" to "ar,en;q=0.9"
    )

    println("=== [ArabSeed] loadLinks START ===")
    println("Movie page URL: $data")

    // 1. Load the main watch page
    val doc = app.get(data, headers = baseHeaders).document
    println("Fetched movie page. Title: ${doc.title()}")

    // 2. Get the post ID
    val postId = doc.selectFirst("ul.qualities__list li")?.attr("data-post")
    println("Extracted postId = $postId")
    if (postId == null) {
        println("!!! ERROR: Could not find data-post on page")
        return false
    }

    // 3. Define qualities
    val qualities = listOf("480", "720", "1080")

    for (q in qualities) {
        try {
            println("=== Trying quality: $q ===")

            // 4. Call the AJAX endpoint
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val body = mapOf(
                "action" to "getquality",
                "post" to postId,
                "server" to "0",
                "quality" to q
            )

            println("POST $ajaxUrl with body $body")

            val json = app.post(
                ajaxUrl,
                data = body,
                headers = baseHeaders
            ).parsed<Map<String, Any?>>()

            println("AJAX Response: $json")

            val iframeUrl = json["server"] as? String
            println("Extracted iframeUrl = $iframeUrl")
            if (iframeUrl == null) {
                println("!!! ERROR: No iframeUrl found for quality $q")
                continue
            }

            // 5. Open iframe
            val iframeDoc = app.get(iframeUrl, headers = mapOf("Referer" to data)).document
            println("Fetched iframe. Title: ${iframeDoc.title()}")

            // 6. Extract video source
            val videoUrl = iframeDoc.selectFirst("video > source")?.attr("src")
            println("Extracted videoUrl = $videoUrl")
            if (videoUrl == null) {
                println("!!! ERROR: No <video> source found in iframe for quality $q")
                continue
            }

            // 7. Callback
            println(">>> SUCCESS: Found video for $q → $videoUrl")
            callback.invoke(
                newExtractorLink(
                    source = "ArabSeed",
                    name = "ArabSeed $q",
                    url = videoUrl,
                    ){
                    referer = iframeUrl
                    quality = q.toIntOrNull() ?: 0
                 //   isM3u8 = videoUrl.endsWith(".m3u8")
                }
            )
        } catch (e: Exception) {
            println("!!! ERROR: Exception while fetching quality $q → ${e.message}")
        }
    }

    println("=== [ArabSeed] loadLinks END ===")
    return true
}

}
