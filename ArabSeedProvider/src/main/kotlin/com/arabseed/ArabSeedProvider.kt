package com.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

        // val posterUrl = selectFirst(".post__image img")?.attr("src")
           // ?.replace("-304x450.webp", ".jpg")

        println("ArabSeedProvider: Parsed search item -> title=$title, posterUrl=$posterUrl")

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // --- Home categories ---
    override val mainPage = mainPageOf(
        "$mainUrl/main0/" to "الرئيسية",
        "$mainUrl/category/foreign-movies-6/" to "افلام اجنبي",
        "$mainUrl/category/asian-movies/" to "افلام اسيوية",
        "$mainUrl/category/arabic-movies-5/" to "افلام عربي",
        "$mainUrl/category/foreign-series-2/" to "مسلسلات اجنبي",
        "$mainUrl/category/arabic-series-2/" to "مسلسلات عربي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%83%d9%88%d8%b1%d9%8a%d9%87/" to "مسلسلات كوريه",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%8a%d9%85%d9%8a%d8%b4%d9%86/" to "افلام انيميشن",
        "$mainUrl/category/cartoon-series/" to "مسلسلات كرتون"
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

        println("ArabSeedProvider: Loading details -> title=$title, poster=$poster")

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

    // --- Extract links with debug ---
        override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    println("ArabSeedProvider: 🔎 loadLinks called with data=$data")

    val doc = app.get(data).document
    println("ArabSeedProvider: ✅ Loaded main page, title=${doc.selectFirst("title")?.text()}")

    val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
        ?: doc.selectFirst("input[name=csrf_token]")?.attr("value")
        ?: ""
    println("ArabSeedProvider: 🔑 CSRF token=$csrfToken")

    val servers = doc.select("div.servers__list li").mapNotNull { li ->
        val postId = li.attr("data-post")
        val quality = li.attr("data-qu")
        println("ArabSeedProvider: ➡️ Found server entry: postId=$postId, quality=$quality")
        if (postId.isNotBlank() && quality.isNotBlank()) {
            Triple(postId, quality, csrfToken)
        } else null
    }

    if (servers.isEmpty()) {
        println("ArabSeedProvider: ❌ No servers found on page")
        return false
    }

    var foundAny = false

    for ((postId, quality, token) in servers) {
        try {
            println("ArabSeedProvider: 🌐 Requesting server for postId=$postId, quality=$quality")

            val resp = app.post(
                url = "$mainUrl/get__quality__servers/",
                data = mapOf(
                    "post_id" to postId,
                    "quality" to quality,
                    "csrf_token" to token
                ),
                referer = data
            )

            val body = resp.text
            println("ArabSeedProvider: 📩 JSON response (first 200 chars): ${body.take(200)}")

            if (body.isNotBlank() && body.trim().startsWith("{")) {
                val json = JSONObject(body)
                val iframeUrl = json.optString("server", null)
                println("ArabSeedProvider: 🖼️ Extracted iframeUrl=$iframeUrl")

                if (!iframeUrl.isNullOrBlank()) {
                    val iframeDoc = app.get(iframeUrl, referer = data).document
                    println("ArabSeedProvider: ✅ Loaded iframe, title=${iframeDoc.selectFirst("title")?.text()}")

                    val sources = iframeDoc.select("source")
                    if (sources.isEmpty()) {
                        println("ArabSeedProvider: ❌ No <source> tags in iframe. Trying regex fallback…")

                        // --- Regex fallback (UQLOAD style) ---
                        val regex = Regex("file\\s*[:=]\\s*\"(https[^\"]+)\"")
                        val match = regex.find(iframeDoc.outerHtml())
                        if (match != null) {
                            val videoUrl = match.groupValues[1]
                            println("ArabSeedProvider: 🎥 Regex extracted video=$videoUrl")

                            foundAny = true
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${quality}p Regex",
                                    url = videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                        } else {
                            println("ArabSeedProvider: ❌ Regex fallback found nothing")
                        }
                    } else {
                        sources.forEach { sourceEl ->
                            val src = sourceEl.attr("src")
                            val label = sourceEl.attr("label").ifBlank { "${quality}p Direct" }
                            println("ArabSeedProvider: 🎥 Found <source>: src=$src, label=$label")

                            if (src.isNotBlank()) {
                                foundAny = true
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = label,
                                        url = src,
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                            }
                        }
                    }

                    println("ArabSeedProvider: ➡️ Passing iframe to other extractors: $iframeUrl")
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                } else {
                    println("ArabSeedProvider: ❌ iframeUrl missing in JSON")
                }
            } else {
                println("ArabSeedProvider: ❌ Invalid JSON response: $body")
            }

        } catch (e: Exception) {
            println("ArabSeedProvider: 💥 Exception for post=$postId quality=$quality -> ${e.message}")
        }
    }

    if (!foundAny) {
        println("ArabSeedProvider: ❌ Finished but no playable links found")
    } else {
        println("ArabSeedProvider: ✅ At least one playable link extracted")
    }

    return foundAny
}


}
