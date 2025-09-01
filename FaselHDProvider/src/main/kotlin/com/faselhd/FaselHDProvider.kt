package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.requestCreator
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

class FaselHD : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://www.faselhds.club"
    private val alternativeUrl = "https://www.faselhds.life"
    override var name = "FaselHD"
    override val usesWebView = false
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)
    private val cfKiller = CloudflareKiller()

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = select("div.postDiv a")
        val url = anchor.attr("href") ?: return null
        val img = anchor.select("div img")
        val posterUrl = img.attr("data-src").ifEmpty { img.attr("src") }
        val titleRaw = img.attr("alt")
        val quality = select(".quality").firstOrNull()?.text()?.replace("1080p |-".toRegex(), "")

        val cleanedTitle = titleRaw.replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي".toRegex(), "")
        val type = if (titleRaw.contains("فيلم")) TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(cleanedTitle, url, type) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

 /* //  override suspend fun getMainPage(): HomePageResponse
    override suspend fun getMainPage(request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val homeLists = ArrayList<HomePageList>()

        val section = doc.select("div#postList")
        val items = section.select("div.postDiv").mapNotNull { it.toSearchResponse() }

        homeLists.add(HomePageList("أحدث الإضافات", items))

        return HomePageResponse(homeLists)
    }*/

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document

        if (doc.select("title").text() == "Just a moment...") {
            doc = app.get(url, interceptor = cfKiller, timeout = 120).document
        }

        val isMovie = doc.select("div.epAll").isEmpty()
        val posterUrl = doc.select("div.posterImg img").attr("src")
            .ifEmpty { doc.select("div.seasonDiv.active img").attr("data-src") }

        val year = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]")
            .firstOrNull { it.text().contains("سنة|موعد".toRegex()) }
            ?.text()?.getIntFromText()

        val title = doc.select("title").text().replace(" - فاصل إعلاني", "")
            .replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|$year".toRegex(), "")

        val duration = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]")
            .firstOrNull { it.text().contains("مدة|توقيت".toRegex()) }
            ?.text()?.getIntFromText()

        val tags = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]:contains(تصنيف الفيلم) a").map {
            it.text()
        }

        val recommendations = doc.select("div#postList div.postDiv").mapNotNull { it.toSearchResponse() }
        val synopsis = doc.select("div.singleDesc p").text()

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
                this.duration = duration
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()
            doc.select("div.epAll a").forEach {
                episodes.add(
                    newEpisode(it.attr("href")) {
                        this.name = it.text()
                        this.season = doc.select("div.seasonDiv.active div.title").text().getIntFromText() ?: 1
                        this.episode = it.text().getIntFromText()
                    }
                )
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.distinct().sortedBy { it.episode }
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.duration = duration
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }
}
