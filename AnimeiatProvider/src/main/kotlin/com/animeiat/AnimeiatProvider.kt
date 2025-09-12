package com.animeiat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.nicehttp.Requests

class Animeiat : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://ww1.animeiat.tv"
    private val pageUrl = "https://www.animeiat.tv"
    override var name = "Animeiat"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    data class Data(
        @JsonProperty("anime_name") var animeName: String? = null,
        @JsonProperty("title") var title: String? = null,
        @JsonProperty("slug") var slug: String? = null,
        @JsonProperty("story") var story: String? = null,
        @JsonProperty("other_names") var otherNames: String? = null,
        @JsonProperty("total_episodes") var totalEpisodes: Int? = null,
        @JsonProperty("number") var number: Int? = null,
        @JsonProperty("age") var age: String? = null,
        @JsonProperty("type") var type: String? = null,
        @JsonProperty("status") var status: String? = null,
        @JsonProperty("poster_path") var posterPath: String? = null
    )

    data class All(@JsonProperty("data") var data: ArrayList<Data> = arrayListOf())

    override val mainPage = mainPageOf(
        "$mainUrl/home/sticky-episodes?page=" to "Episodes (H)",
        "$mainUrl/anime?status=completed&page=" to "Completed",
        "$mainUrl/anime?status=ongoing&page=" to "Ongoing"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = parseJson<All>(app.get(request.data + page).text)
        val list = json.data.map {
            newAnimeSearchResponse(
                it.animeName ?: it.title ?: "Unknown Title",
                "$mainUrl/anime/${it.slug?.replace("-episode.*".toRegex(), "")}",
                when (it.type) {
                    "movie" -> TvType.AnimeMovie
                    "tv" -> TvType.Anime
                    else -> TvType.OVA
                }
            ) {
                addDubStatus(false, it.totalEpisodes ?: it.number ?: 1)
                otherName = it.otherNames?.split("\n")?.last()
                posterUrl = "https://api.animeiat.co/storage/" + it.posterPath
            }
        }
        return if (request.name.contains("(H)")) {
            HomePageResponse(
                listOf(
                    HomePageList(
                        request.name.replace(" (H)", ""),
                        list,
                        true
                    )
                )
            )
        } else {
            newHomePageResponse(request.name, list)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = parseJson<All>(app.get("$mainUrl/anime?q=$query").text)
        return json.data.map {
            newAnimeSearchResponse(
                it.animeName ?: it.title ?: "Unknown Title",
                "$mainUrl/anime/${it.slug}",
                when (it.type) {
                    "movie" -> TvType.AnimeMovie
                    "tv" -> TvType.Anime
                    else -> TvType.OVA
                }
            ) {
                addDubStatus(false, it.totalEpisodes ?: 1)
                otherName = it.otherNames?.split("\n")?.last()
                posterUrl = "https://api.animeiat.co/storage/" + it.posterPath
            }
        }
    }

    data class Year(@JsonProperty("name") var name: String? = null)
    data class Genres(@JsonProperty("name") var name: String? = null)

    data class LoadData(
        @JsonProperty("anime_name") var animeName: String? = null,
        @JsonProperty("slug") var slug: String? = null,
        @JsonProperty("story") var story: String? = null,
        @JsonProperty("other_names") var otherNames: String? = null,
        @JsonProperty("age") var age: String? = null,
        @JsonProperty("type") var type: String? = null,
        @JsonProperty("status") var status: String? = null,
        @JsonProperty("poster_path") var posterPath: String? = null,
        @JsonProperty("year") var year: Year? = null,
        @JsonProperty("genres") var genres: ArrayList<Genres> = arrayListOf()
    )

    data class Load(@JsonProperty("data") var data: LoadData? = null)
    data class Meta(@JsonProperty("last_page") var lastPage: Int? = null)

    data class EpisodeData(
        @JsonProperty("title") var title: String? = null,
        @JsonProperty("slug") var slug: String? = null,
        @JsonProperty("number") var number: Int? = null,
        @JsonProperty("video_id") var videoId: Int? = null,
        @JsonProperty("poster_path") var posterPath: String? = null
    )

    data class Episodes(
        @JsonProperty("data") var data: ArrayList<EpisodeData> = arrayListOf(),
        @JsonProperty("meta") var meta: Meta = Meta()
    )

    override suspend fun load(url: String): LoadResponse {
        val session = Requests()
        val json = parseJson<Load>(session.get(url.replace(pageUrl, mainUrl)).text)
        val episodes = ArrayList<Episode>()

        val lastPage = parseJson<Episodes>(session.get("$url/episodes").text).meta.lastPage ?: 1
        for (pageNumber in 1..lastPage) {
    val episodePage = parseJson<Episodes>(
        session.get("$url/episodes?page=$pageNumber").text
    )

    episodes += episodePage.data.map {
        newEpisode("$pageUrl/watch/${json.data?.slug}") {
            name = it.title ?: "Episode ${it.number}"
            episode = it.number
            posterUrl = "https://api.animeiat.co/storage/" + it.posterPath
        }
    }
}

        return newAnimeLoadResponse(
            json.data?.animeName ?: "Unknown Title",
            "$pageUrl/watch/${json.data?.slug}",
            when (json.data?.type) {
                "movie" -> TvType.AnimeMovie
                "tv" -> TvType.Anime
                else -> TvType.OVA
            }
        ) {
            japName = json.data?.otherNames?.replace("\\n.*".toRegex(), "")
            engName = json.data?.animeName
            posterUrl = "https://api.animeiat.co/storage/" + json.data?.posterPath
            year = json.data?.year?.name?.toIntOrNull()
            addEpisodes(DubStatus.Subbed, episodes)
            plot = json.data?.story
            tags = json.data?.genres?.mapNotNull { it.name }
            showStatus = if (json.data?.status == "completed") ShowStatus.Completed else ShowStatus.Ongoing
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = if (data.contains("-episode")) data else "$data-episode-1"
        val doc = app.get(url).document
        val script = doc.select("body > script").first()?.html()
        val id = script?.replace(".*4\",slug:\"|\",duration:.*".toRegex(), "")
        val player = app.get("$pageUrl/player/$id").document

        player.select("source").forEach {
            val videoUrl = it.attr("src") ?: return@forEach
            val quality = it.attr("size").toIntOrNull() ?: 720

            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = pageUrl,
                    quality = quality,
                    type = ExtractorLinkType.M3U8
                )
            )
        }

        return true
    }
}
