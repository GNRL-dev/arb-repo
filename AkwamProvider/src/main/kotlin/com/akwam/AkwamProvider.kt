package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder




class Akwam : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://ak.sv"
    override var name = "Akwam"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.Cartoon)

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = select("a.box").attr("href") ?: return null
        if (url.contains("/games/") || url.contains("/programs/")) return null
        val poster = select("picture > img")
        val title = poster.attr("alt") ?: return null
        val posterUrl = poster.attr("data-src")
        val year = select(".badge-secondary").text().toIntOrNull()

        return newMovieSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "Movies",
        "$mainUrl/series?page=" to "Series",
        "$mainUrl/shows?page=" to "Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val list = doc.select("div.col-lg-auto.col-md-4.col-6.mb-12").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, list)
    }

   /* override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url).document
        return doc.select("div.col-lg-auto").mapNotNull {
            it.toSearchResponse()
        }
    }*/
    override suspend fun search(query: String, page: Int): SearchResponseList? {
    val url = "$mainUrl/search?q=${query.encodeURL()}&page=$page"
    val doc = app.get(url).document

    val results = doc.select("div.col-lg-auto").mapNotNull {
        it.toSearchResponse()
    }

    return SearchResponseList(results)
    }
 /*  override suspend fun search(query: String, page: Int): SearchResponseList? {
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    val url = "$mainUrl/search?q=$encodedQuery&page=$page"

    val doc = app.get(url).document

    val results = doc.select("div.col-lg-auto").mapNotNull {
        it.toSearchResponse()
    }

    return newSearchResponseList(results)
}*/


    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toEpisode(): Episode {
        val a = select("a.text-white")
        val url = a.attr("href")
        val title = a.text()
        val thumbUrl = select("picture > img").attr("src")
        val date = select("p.entry-date").text()
        return newEpisode(url) {
            name = title
            episode = title.getIntFromText()
            posterUrl = thumbUrl
            addDate(date)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val isMovie = doc.select("#downloads > h2 > span").isNotEmpty()
        val title = doc.select("h1.entry-title").text()
        val posterUrl = doc.select("picture > img").attr("src")

        val year = doc.select("div.font-size-16.text-white.mt-2").firstOrNull {
            it.text().contains("السنة")
        }?.text()?.getIntFromText()

        val duration = doc.select("div.font-size-16.text-white.mt-2").firstOrNull {
            it.text().contains("مدة الفيلم")
        }?.text()?.getIntFromText()

        val synopsis = doc.select("div.widget-body p:first-child").text()

//        val rating = doc.select("span.mx-2").text().split("/").lastOrNull()?.toRatingInt()

        val tags = doc.select("div.font-size-16.d-flex.align-items-center.mt-3 > a").map {
            it.text()
        }

        val actors = doc.select("div.widget-body > div > div.entry-box > a").mapNotNull {
            val name = it?.selectFirst("div > .entry-title")?.text() ?: return@mapNotNull null
            val image = it.selectFirst("div > img")?.attr("src") ?: return@mapNotNull null
            Actor(name, image)
        }

        val recommendations =
            doc.select("div > div.widget-body > div.row > div > div.entry-box").mapNotNull {
                val recTitle = it?.selectFirst("div.entry-body > .entry-title > .text-white")
                    ?: return@mapNotNull null
                val href = recTitle.attr("href") ?: return@mapNotNull null
                val name = recTitle.text() ?: return@mapNotNull null
                val poster = it.selectFirst(".entry-image > a > picture > img")?.attr("data-src")
                    ?: return@mapNotNull null
                newMovieSearchResponse(name, href, TvType.Movie) {
                    this.posterUrl = fixUrl(poster)
                }
            }

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
              //  this.score = rating
             //   this.score = rating?.let { Score.fromRating(it) }
             //   this.score = rating?.let { Score(it) }
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            val episodes = doc.select("div.bg-primary2.p-4.col-lg-4.col-md-6.col-12").map {
                it.toEpisode()
            }.let {
                val isReversed = (it.lastOrNull()?.episode ?: 1) < (it.firstOrNull()?.episode ?: 0)
                if (isReversed) it.reversed() else it
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.duration = duration
                this.posterUrl = posterUrl
                this.tags = tags.filterNotNull()
               // this.rating = rating
              // this.score = rating?.let { Score.fromRating(it) }
              //  this.score = rating?.let { Score(it) }
                this.year = year
                this.plot = synopsis
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val doc = app.get(data).document

    val links = doc.select("div.tab-content.quality").flatMap { element ->
        val quality = getQualityFromId(element.attr("id").getIntFromText())
        element.select(".col-lg-6 > a:contains(تحميل)").map { linkElement ->
            val href = linkElement.attr("href")
            if (href.contains("/download/")) {
                href to quality
            } else {
                val suffix = data.split("/movie|/episode|/shows|/show/episode".toRegex())[1]
                "$mainUrl/download${href.split("/link")[1]}$suffix" to quality
            }
        }
    }

    links.forEach { (linkUrl, quality) ->
        val linkDoc = app.get(linkUrl).document
        val button = linkDoc.select("div.btn-loader > a")
        val finalUrl = button.attr("href")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl,
              //  type = ExtractorLinkType.MP4, // Or MP4 if applicable
                //referer = this.mainUrl
            ) {
                this.referer = this@Akwam.mainUrl // 'this@Akwam' to reference outer class
                this.quality = quality.value
              //  this.isM3u8 = true // or false based on actual stream type
            }
        )
    }

    return true
}

/*override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val doc = app.get(data).document

    val links = doc.select("div.tab-content.quality").flatMap { element ->
        val quality = getQualityFromId(element.attr("id").getIntFromText())
        element.select(".col-lg-6 > a:contains(تحميل)").map { linkElement ->
            val href = linkElement.attr("href")
            if (href.contains("/download/")) {
                href to quality
            } else {
                val suffix = data.split("/movie|/episode|/shows|/show/episode".toRegex())[1]
                "$mainUrl/download${href.split("/link")[1]}$suffix" to quality
            }
        }
    }

    links.forEach { (linkUrl, quality) ->
        val linkDoc = app.get(linkUrl).document
        val button = linkDoc.select("div.btn-loader > a")
        val finalUrl = button.attr("href")

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                finalUrl,
                this.mainUrl,
                quality.value,
                false
            )
        )
    }

    return true
}
*/
    /*
    // Optional loadLinks function - commented out as requested
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(data).document

        val links = doc.select("div.tab-content.quality").flatMap { element ->
            val quality = getQualityFromId(element.attr("id").getIntFromText())
            element.select(".col-lg-6 > a:contains(تحميل)").map { linkElement ->
                val href = linkElement.attr("href")
                if (href.contains("/download/")) {
                    href to quality
                } else {
                    val suffix = data.split("/movie|/episode|/shows|/show/episode".toRegex())[1]
                    "$mainUrl/download${href.split("/link")[1]}$suffix" to quality
                }
            }
        }

        links.forEach { (linkUrl, quality) ->
            val linkDoc = app.get(linkUrl).document
            val button = linkDoc.select("div.btn-loader > a")
            val finalUrl = button.attr("href")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalUrl,
                    referer = this.mainUrl,
                    quality = quality.value,
                    type = null,
                    isM3u8 = false
                )
            )
        }
       // return true
    }*/
    

    private fun getQualityFromId(id: Int?): Qualities {
        return when (id) {
            2 -> Qualities.P360
            3 -> Qualities.P480
            4 -> Qualities.P720
            5 -> Qualities.P1080
            else -> Qualities.Unknown
        }
    }
}
