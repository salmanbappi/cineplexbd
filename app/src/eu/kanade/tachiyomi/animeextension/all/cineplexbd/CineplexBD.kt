package eu.kanade.tachiyomi.animeextension.all.cineplexbd

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder

class CineplexBD : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Cineplex BD"
    override val baseUrl = "http://cineplexbd.net"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 5181466391484419848L

    private val json: Json by lazy { Injekt.get() }

    // Use search for popular/latest to support pagination and "All" listing
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/search.php?q=&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        var category = ""
        var type = ""

        for (filter in filters) {
            when (filter) {
                is CategoryFilter -> category = filter.toUriPart()
                is TypeFilter -> type = filter.toUriPart()
                else -> {}
            }
        }

        // Specific category logic
        if (category.isNotEmpty()) {
            val cleanCategory = URLEncoder.encode(category, "UTF-8")
            val baseEndpoint = if (type == "tv" || category.contains("Series") || category.contains("Shows")) {
                "tcategory.php"
            } else {
                "category.php"
            }
            return GET("$baseUrl/$baseEndpoint?category=$cleanCategory&page=$page")
        }

        return GET("$baseUrl/search.php?q=$encodedQuery&page=$page")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        
        doc.select("a[href^='view.php'], a[href^='watch.php'], a[href^='tview.php']").forEach { element ->
            animeList.add(parseAnimeItem(element))
        }
        
        // Pagination: Check for a "Next" button or standard pagination links
        val hasNextPage = doc.select("ul.pagination li.active + li a, a:contains(Next), a:contains(»), a.next").isNotEmpty()
        
        return AnimesPage(animeList.distinctBy { it.url }, hasNextPage)
    }

    private fun parseAnimeItem(element: Element): SAnime {
        return SAnime.create().apply {
            url = element.attr("href")
            val titleEl = element.selectFirst("span.truncate, div.text-sm, div.cp-title, h2, .card-title")
            title = titleEl?.text() ?: element.selectFirst("img")?.attr("alt") ?: "Unknown Title"
            thumbnail_url = element.selectFirst("img")?.attr("src")?.let {
                if (it.startsWith("http")) it else "$baseUrl/${it.trimStart('/')}"
            }
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        return GET(baseUrl + url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            val rawTitle = doc.selectFirst("h1, .movie-title, title")?.text()?.replace(" — Watch", "") ?: ""
            title = rawTitle
            description = doc.selectFirst("p.leading-relaxed, #synopsis")?.text() ?: ""
            val genreStr = doc.select("div.ganre-wrapper a, .meta-cat").joinToString { it.text() }
            genre = genreStr
            status = SAnime.COMPLETED
            thumbnail_url = doc.selectFirst("img.poster, .tvCard img")?.attr("src")?.let {
                if (it.startsWith("http")) it else "$baseUrl/${it.trimStart('/')}"
            }
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        return GET(baseUrl + url, headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val episodes = mutableListOf<SEpisode>()

        if (url.contains("view.php") || url.contains("tview.php")) {
            // Movie Logic
            val id = url.substringAfter("id=")
            episodes.add(SEpisode.create().apply {
                name = "Movie"
                episode_number = 1f
                this.url = "/player.php?id=$id"
            })
        } else if (url.contains("watch.php")) {
            val doc = response.asJsoup()
            val id = url.substringAfter("id=").substringBefore("&")
            
            // Find all seasons
            val seasonOptions = doc.select("select[name=season] option")
            val seasons = if (seasonOptions.isNotEmpty()) {
                seasonOptions.map { it.attr("value") }
            } else {
                listOf("1")
            }

            // Fetch episodes for EACH season
            seasons.forEach { season ->
                try {
                    val metaUrl = "$baseUrl/watch.php?id=$id&season=$season&meta=1"
                    val metaResponse = client.newCall(GET(metaUrl, headers)).execute()
                    val metaJson = json.decodeFromString<JsonObject>(metaResponse.body.string())
                    
                    val epsObj = metaJson["episodes"]?.jsonObject
                    // Convert to list to sort by episode number/key within the season
                    val seasonEps = epsObj?.entries?.mapNotNull { (key, value) ->
                        try {
                            val epJson = value.jsonObject
                            val rawName = epJson["title"]?.jsonPrimitive?.content ?: "Episode $key"
                            val epPath = epJson["path"]?.jsonPrimitive?.content ?: ""
                            
                            // Try to parse episode number from JSON or key
                            var epNum = epJson["episode_number"]?.jsonPrimitive?.content?.toFloatOrNull() 
                                ?: key.toFloatOrNull() 
                                ?: 0f
                            
                            // Fallback: Extract from name if 0 (e.g. S01E05)
                            if (epNum == 0f) {
                                val match = Regex("""(?i)E(\d+)""").find(rawName)
                                epNum = match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                            }

                            SEpisode.create().apply {
                                name = rawName
                                episode_number = epNum
                                this.url = epPath
                            }
                        } catch (e: Exception) { null }
                    }?.sortedBy { it.episode_number } // Sort episodes WITHIN the season

                    if (seasonEps != null) {
                        episodes.addAll(seasonEps)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        // DO NOT sort the final list globally. Keep Season 1 -> Season 2 order.
        return episodes
    }

    override fun videoListRequest(episode: SEpisode): Request {
        if (episode.url.startsWith("http")) {
            return GET(episode.url, headers)
        }
        val url = if (episode.url.startsWith("/")) episode.url else "/${episode.url}"
        return GET(baseUrl + url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        
        if (url.endsWith(".mp4") || url.endsWith(".mkv") || url.contains("/Data/")) {
            return listOf(Video(url, "Direct", url))
        }

        val doc = response.asJsoup()
        val videoUrl = doc.selectFirst("source[type='video/mp4'], source")?.attr("src")
        
        if (!videoUrl.isNullOrBlank()) {
            val finalUrl = if (videoUrl.startsWith("http")) videoUrl else "$baseUrl/${videoUrl.trimStart('/')}"
            return listOf(Video(finalUrl, "Original", finalUrl))
        }
        
        return emptyList()
    }

    // Filters
    override fun getFilterList() = AnimeFilterList(
        TypeFilter(),
        CategoryFilter()
    )

    private class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf("All", "Movies", "TV Series")) {
        fun toUriPart() = when(state) {
            1 -> "movie"
            2 -> "tv"
            else -> ""
        }
    }

    private class CategoryFilter : AnimeFilter.Select<String>("Category", arrayOf(
        "Any",
        "3D Movies", "4K Movies", "Animation", "Anime", "Bangla", "Bangla Dubbed", "Bangla Movies",
        "Chinese", "Documentaries", "Dual Audio", "English", "Exclusive Full HD", "Foreign",
        "Hindi", "Indonesian", "Japanese", "Kids Cartoon", "Korean", "Pakistani", "Punjabi", "Romance",
        "Hindi Dubbed/Chinees Movies", "Hindi Dubbed/English Movies", "Hindi Dubbed/Indonesian Movies",
        "Hindi Dubbed/Japanese Movies", "Hindi Dubbed/Korean Movies", "Hindi Dubbed/Tamil Movies",
        "Animation Series", "Bangla Animation", "Hindi Animation", "English Animation", "Others Animation",
        "Award Shows", "Bangla Shows", "English Shows", "Hindi Shows", "Others Shows",
        "Bangla Series", "Bangla Drama", "Entertainment", "Indian Bangla", "Indian Bangla Drama",
        "Documentary", "Islamic Series", "Bangla Dubbed", "Hindi Dubbed",
        "Web Series", "Hindi Series", "Pakistani Series", "English Series", "Korean Series", "Japanese Series", "Others Series",
        "WWE", "AEW Wrestling", "WWE Wrestling"
    )) {
        fun toUriPart() = if (state == 0) "" else values[state]
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}