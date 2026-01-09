package eu.kanade.tachiyomi.animeextension.all.cineplexbd

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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import androidx.preference.PreferenceScreen
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CineplexBD : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Cineplex BD"
    override val baseUrl = "http://cineplexbd.net"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 5181466391484419848L

    private val json: Json by lazy { Injekt.get() }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/index.php")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        
        // Parse grid items (Movies and TV)
        doc.select("a[href^='view.php'], a[href^='watch.php']").forEach { element ->
            animeList.add(parseAnimeItem(element))
        }
        
        return AnimesPage(animeList.distinctBy { it.url }, false)
    }

    private fun parseAnimeItem(element: Element): SAnime {
        return SAnime.create().apply {
            url = element.attr("href")
            val titleEl = element.selectFirst("span.truncate, div.text-sm, div.cp-title, h2")
            title = titleEl?.text() ?: element.selectFirst("img")?.attr("alt") ?: "Unknown Title"
            thumbnail_url = element.selectFirst("img")?.attr("src")?.let {
                if (it.startsWith("http")) it else "$baseUrl/$it"
            }
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        return GET(baseUrl + url, headers)
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        return GET(baseUrl + url, headers)
    }

    override fun videoListRequest(episode: SEpisode): Request {
        if (episode.url.startsWith("http")) {
            return GET(episode.url, headers)
        }
        val url = if (episode.url.startsWith("/")) episode.url else "/${episode.url}"
        return GET(baseUrl + url, headers)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        var category = ""

        for (filter in filters) {
            if (filter is CategoryFilter) {
                category = filter.toUriPart()
            }
        }

        return if (category.isNotEmpty() && query.isEmpty()) {
            val cleanCategory = URLEncoder.encode(category, "UTF-8")
            // Determine if it's a TV category or Movie category for the base URL
            // Using a heuristic or checking all known TV categories
            if (category.contains("Series") || category.contains("Shows") || category.contains("WWE") || category.contains("Wrestling")) {
                GET("$baseUrl/tcategory.php?category=$cleanCategory")
            } else {
                GET("$baseUrl/category.php?category=$cleanCategory")
            }
        } else {
            GET("$baseUrl/search.php?q=$encodedQuery")
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        
        // Parse grid items (Movies and TV)
        doc.select("a[href^='view.php'], a[href^='watch.php'], a[href^='tview.php']").forEach { element ->
            val item = parseAnimeItem(element)
            animeList.add(item)
        }
        
        return AnimesPage(animeList.distinctBy { it.url }, false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            val rawTitle = doc.selectFirst("h1, .movie-title, title")?.text()?.replace(" — Watch", "") ?: ""
            description = doc.selectFirst("p.leading-relaxed, #synopsis")?.text() ?: ""
            val genreStr = doc.select("div.ganre-wrapper a, .meta-cat").joinToString { it.text() }
            genre = genreStr
            
            // Check for 4K in title, genre, or badges
            val is4k = rawTitle.contains("4K", true) || 
                       genreStr.contains("4K", true) ||
                       doc.select(".meta-badge, .rounded.shadow-md").any { it.text().contains("4K", true) }

            title = if (is4k && !rawTitle.contains("4K", true)) "$rawTitle (4K)" else rawTitle
            status = SAnime.COMPLETED
            thumbnail_url = doc.selectFirst("img.poster, .tvCard img")?.attr("src")?.let {
                if (it.startsWith("http")) it else "$baseUrl/$it"
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val episodes = mutableListOf<SEpisode>()

        if (url.contains("view.php")) {
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
                listOf("1") // Default to season 1 if no selector
            }

            // Fetch episodes for EACH season
            seasons.forEach { season ->
                try {
                    val metaUrl = "$baseUrl/watch.php?id=$id&season=$season&meta=1"
                    val metaResponse = client.newCall(GET(metaUrl, headers)).execute()
                    val metaJson = json.decodeFromString<JsonObject>(metaResponse.body.string())
                    
                    val epsObj = metaJson["episodes"]?.jsonObject
                    epsObj?.entries?.forEach { (key, value) ->
                        val epJson = value.jsonObject
                        episodes.add(SEpisode.create().apply {
                            val rawName = epJson["title"]?.jsonPrimitive?.content ?: "Episode $key"
                            name = cleanEpisodeName(rawName)
                            
                            val epNum = epJson["episode_number"]?.jsonPrimitive?.content?.toFloatOrNull() 
                                ?: key.toFloatOrNull() 
                                ?: 0f
                            episode_number = epNum
                            this.url = epJson["path"]?.jsonPrimitive?.content ?: ""
                        })
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return episodes.sortedBy { it.episode_number }
    }

    private fun cleanEpisodeName(rawName: String): String {
        // Example: Loki.S01E01.Glorious.Purpose.1080p.10bit.WEBRip...
        // Target: S01E01 Glorious Purpose
        try {
            // Check for SxxExx pattern
            val regex = Regex("""(?i)(S\d+E\d+)(.*?)(\d{3,4}p|\d+bit|WEBRip|HEVC|x264|x265)""")
            val match = regex.find(rawName)
            if (match != null) {
                val sXeX = match.groupValues[1]
                val titlePart = match.groupValues[2].replace(".", " ").trim()
                return "$sXeX $titlePart"
            }
        } catch (e: Exception) {
            return rawName
        }
        return rawName.replace(".", " ")
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        
        // If it's a direct mp4 path from the meta API (Series)
        if (url.endsWith(".mp4") || url.endsWith(".mkv") || url.contains("/Data/")) {
            return listOf(Video(url, "Direct", url))
        }

        // If it's player.php (for movies)
        val doc = response.asJsoup()
        val videoUrl = doc.selectFirst("source[type='video/mp4'], source")?.attr("src")
        
        if (!videoUrl.isNullOrBlank()) {
            val finalUrl = if (videoUrl.startsWith("http")) videoUrl else "$baseUrl${if(videoUrl.startsWith("/")) "" else "/"}$videoUrl"
            return listOf(Video(finalUrl, "Original", finalUrl))
        }
        
        return emptyList()
    }

    // Filters
    override fun getFilterList() = AnimeFilterList(
        CategoryFilter()
    )

    private class CategoryFilter : AnimeFilter.Select<String>("Category", arrayOf(
        "Show All",
        "3D Movies", "4K Movies", "Animation", "Anime", "Bangla", "Bangla Dubbed", "Bangla Movies",
        "Chinese", "Documentaries", "Dual Audio", "English", "Exclusive Full HD", "Foreign",
        "Hindi", "Indonesian", "Japanese", "Kids Cartoon", "Korean", "Pakistani", "Punjabi", "Romance",
        "Hindi Dubbed/Chinees Movies", "Hindi Dubbed/English Movies", "Hindi Dubbed/Indonesian Movies",
        "Hindi Dubbed/Japanese Movies", "Hindi Dubbed/Korean Movies", "Hindi Dubbed/Tamil Movies",
        "Animation Series", "Bangla Animation", "Hindi Animation", "English Animation", "Others Animation",
        "Award Shows", "Bangla Shows", "English Shows", "Hindi Shows", "Others Shows",
        "Bangla Series", "Bangla Drama", "Entertainment", "Indian Bangla", "Indian Bangla Drama",
        "Documentary",
        "Islamic Series", "Bangla Dubbed", "Hindi Dubbed",
        "Web Series", "Hindi Series", "Pakistani Series", "English Series", "Korean Series", "Japanese Series", "Others Series",
        "WWE", "AEW Wrestling", "WWE Wrestling"
    )) {
        fun toUriPart() = if (values[state] == "Show All") "" else values[state]
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}