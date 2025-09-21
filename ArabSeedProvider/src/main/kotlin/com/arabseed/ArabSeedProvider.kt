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
    println("🚀 [COMPLEX] ENTERING loadLinks(data=$data)")

    var foundAny = false
    try {
        val doc = debugGetDocument(data, "COMPLEX")

        val watchUrl = doc.selectFirst("a.watch__btn")?.attr("abs:href")
            ?: doc.selectFirst("a.btn-watch")?.attr("abs:href")
            ?: doc.selectFirst("a[href*=\"/play/\"]")?.attr("abs:href")
            ?: doc.selectFirst("a[href*=\"/video/\"]")?.attr("abs:href")

        if (watchUrl.isNullOrBlank()) {
            println("⚠️ [COMPLEX] No watch URL found in page=$data")
            return foundAny
        }

        println("🎬 [COMPLEX] Found watchUrl = $watchUrl")

        val watchDoc = debugGetDocument(watchUrl, "COMPLEX-WATCH")
        val iframes = watchDoc.select("iframe[src]").map { it.attr("abs:src") }

        println("➡️ [COMPLEX] Found ${iframes.size} iframe(s) from watch page")

        for (iframe in iframes) {
            println("🌐 [COMPLEX] Checking iframe = $iframe")
            val iframeDoc = debugGetDocument(iframe, "COMPLEX-IFRAME")

            val qualities = iframeDoc.select("ul.qualities__list li")
            val postId = iframeDoc.selectFirst("input[name=post_id]")?.attr("value")
            val csrfToken = iframeDoc.selectFirst("input[name=csrf_token]")?.attr("value")

            if (qualities.isEmpty() || postId.isNullOrBlank() || csrfToken.isNullOrBlank()) {
                println("⚠️ [COMPLEX] Missing qualities/tokens in iframe=$iframe")
                continue
            }

            println("🎚️ [COMPLEX] Found quality switcher with ${qualities.size} options")

            for (q in qualities) {
                val quality = q.attr("data-quality")
                if (quality.isBlank()) continue

                try {
                    println("🔍 [COMPLEX] Requesting quality=$quality")

                    val resp = app.post(
                        url = "$mainUrl/get__quality__servers/",
                        data = mapOf(
                            "post_id" to postId,
                            "quality" to quality,
                            "csrf_token" to csrfToken
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                        ),
                        referer = iframe
                    )

                    val body = resp.text
                    if (body.isNotBlank() && body.trim().startsWith("{")) {
                        val json = JSONObject(body)
                        val embedUrl = json.optString("server", null)

                        println("🖼️ [COMPLEX] Got embedUrl=$embedUrl for quality=${quality}p")

                        if (!embedUrl.isNullOrBlank()) {
                            val embedDoc = debugGetDocument(embedUrl, "COMPLEX-EMBED")
                            val src = embedDoc.selectFirst("video > source")?.attr("src")

                            if (!src.isNullOrBlank()) {
                                println("✅ [COMPLEX] FINAL LINK FOUND: $src (quality=${quality}p)")
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${quality}p Direct",
                                        url = src,
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                                return true
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("❌ [COMPLEX] Error loading quality=$quality → ${e.message}")
                }
            }
        }
    } catch (e: Exception) {
        println("❌ [COMPLEX] Error in loadLinks → ${e.message}")
    }

    println("🚫 [COMPLEX] No valid links extracted")
    return false
}


}
