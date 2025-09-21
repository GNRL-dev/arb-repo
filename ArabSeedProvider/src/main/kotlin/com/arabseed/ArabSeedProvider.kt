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

suspend fun debugGetDocument(url: String, tag: String): org.jsoup.nodes.Document {
    val doc = app.get(url).document

    println("=== DEBUG [$tag] HTML START ===")
    println(doc.outerHtml().take(2000))
    println("=== DEBUG [$tag] HTML END ===")

    println("=== DEBUG [$tag] ALL <a> TAGS ===")
    doc.select("a").forEach { a: org.jsoup.nodes.Element ->
        val rawHref = a.attr("href")
        val absHref = a.attr("abs:href")
        println("Anchor: ${a.outerHtml()}")
        println("   raw href = $rawHref")
        println("   abs href = $absHref")
    }
    println("=== DEBUG [$tag] END ALL <a> TAGS ===")

    return doc
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

    // 1. Load the main watch page
    val doc = app.get(data, headers = baseHeaders).document

    // 2. Get the post ID (needed for getquality call)
    val postId = doc.selectFirst("ul.qualities__list li")?.attr("data-post")
        ?: return false

    // 3. Loop over the qualities you want
    val qualities = listOf("480", "720", "1080")
    for (q in qualities) {
        try {
            // 4. Call the AJAX endpoint used by the site
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val body = mapOf(
                "action" to "getquality",
                "post" to postId,
                "server" to "0",   // first server (you can loop over more if you want)
                "quality" to q
            )

            val json = app.post(
                ajaxUrl,
                data = body,
                headers = baseHeaders
            ).parsed<Map<String, Any?>>()

            val iframeUrl = json["server"] as? String ?: continue

            // 5. Open the iframe page
            val iframeDoc = app.get(iframeUrl, headers = mapOf("Referer" to data)).document

            // 6. Extract the real video URL from <video><source>
            val videoUrl = iframeDoc.selectFirst("video > source")?.attr("src")
                ?: continue

            // 7. Return the link to Cloudstream
            callback.invoke(
                newExtractorLink(
                    source = "ArabSeed",
                    name = "ArabSeed $q",
                    url = videoUrl,
                    ){
                    referer = iframeUrl
                    quality = q.toIntOrNull() ?: 0
                   // isM3u8 = videoUrl.endsWith(".m3u8")
                }
            )
        } catch (e: Exception) {
            println("Error fetching quality $q → ${e.message}")
        }
    }

    return true
}


}
