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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/category.php?category=Exclusive%20Full%20HD&page=$page")
    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search.php".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())

        var category = ""
        var isTv = false

        for (filter in filters) {
            when (filter) {
                is MovieCategoryFilter -> {
                    if (filter.state != 0) {
                        category = filter.toUriPart()
                        isTv = false
                    }
                }
                is TvCategoryFilter -> {
                    if (filter.state != 0) {
                        category = filter.toUriPart()
                        isTv = true
                    }
                }
                is AnimationCategoryFilter -> {
                    if (filter.state != 0) {
                        category = filter.toUriPart()
                        isTv = true
                    }
                }
                is ShowsCategoryFilter -> {
                    if (filter.state != 0) {
                        category = filter.toUriPart()
                        isTv = true
                    }
                }
                is YearFilter -> {
                    filter.state.forEach { 
                        if (it.state) {
                            url.addQueryParameter("year[]", it.name)
                        }
                    }
                }
                is GenreFilter -> {
                    filter.state.forEach {
                        if (it.state) {
                            url.addQueryParameter("genre[]", it.name)
                        }
                    }
                }
                else -> {}
            }
        }

        if (category.isNotEmpty()) {
            val cleanCategory = URLEncoder.encode(category, "UTF-8")
            val endpoint = if (isTv) "tcategory.php" else "category.php"
            // Category pages might not support complex filtering like search.php does, 
            // but the user asked for filters. We'll prioritize search.php if filters are used,
            // or fallback to category browsing if only category is selected.
            // However, the current structure separates them. 
            // Let's keep existing category logic but if other filters are present, we might need search.php.
            // For now, preserving category behavior as it seems specific.
            return GET("$baseUrl/$endpoint?category=$cleanCategory&page=$page")
        }

        return GET(url.build().toString())
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        
        doc.select("a[href*='view.php'], a[href*='watch.php'], a[href*='tview.php'], .movie-card a, a:has(.poster)").forEach { element ->
            val item = parseAnimeItem(element)
            // Filter out Bangla content from Popular/Latest (which use this parse method)
            // assuming "Popular" is just searchAnimeParse with empty query.
            val isBangla = item.title.contains("Bangla", ignoreCase = true) || 
                           item.url.contains("Bangla", ignoreCase = true)
            
            // We only want to filter Bangla if it's NOT a specific Bangla search.
            // But searchAnimeParse doesn't know the query. 
            // We'll filter it if the request was likely 'Popular' or 'Latest' (no query usually).
            // For now, applying the user's request "avoid bangle related movie or TV series in popular and latest section".
            // Since we can't easily distinguish the context here without more state, 
            // and the user said "don't remove it from any other places", 
            // we should strictly be careful. 
            // A safe bet: The user wants this globally in the list view? 
            // "don't remove it from any other places" implies searches for Bangla should work.
            // I will skip this filtering here and move it to popularAnimeParse/latestUpdatesParse 
            // by refactoring them to use a separate parse method or filtering the result of this one.
            
            if (item.title != "Unknown Title") {
                animeList.add(item)
            }
        }
        
        val hasNextPage = doc.select("ul.pagination li.active + li a, a:contains(Next), a:contains(»), a.next").isNotEmpty()
        return AnimesPage(animeList.distinctBy { it.url }, hasNextPage)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = searchAnimeParse(response)
        val filtered = page.anime.filterNot { 
            it.title.contains("Bangla", ignoreCase = true) 
        }
        return AnimesPage(filtered, page.hasNextPage)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    private fun parseAnimeItem(element: Element): SAnime {
        return SAnime.create().apply {
            url = element.attr("href")
            val titleEl = element.selectFirst("span.truncate, div.text-sm, div.cp-title, h2, .card-title, .title")
            val posterImg = element.selectFirst("img.poster, .tvCard img, img[class*='poster'], img[src*='uploads/']")
            title = titleEl?.text() ?: posterImg?.attr("alt") ?: "Unknown Title"
            
            val rawImg = posterImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: posterImg?.attr("src")
                ?: element.selectFirst("img")?.attr("data-src")
                ?: element.selectFirst("img")?.attr("src")

            thumbnail_url = rawImg?.let {
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
            description = doc.selectFirst("p.leading-relaxed, #synopsis, .description")?.text() ?: ""
            
            // Metadata parsing
            genre = doc.select("span.chip:contains(,)").text().trim() // e.g., "Drama, Family"
            if (genre.isNullOrBlank()) {
                 genre = doc.select("div.ganre-wrapper a, .meta-cat, .genre a").joinToString { it.text() }
            }
            
            author = doc.select("a[href*='cast.php'][href*='Director']").text() ?: 
                     doc.select("div:contains(Director:) a").text() ?:
                     doc.select("div:contains(Director:)").text().substringAfter("Director:").trim()

            // Status is generally Completed for movies
            status = SAnime.COMPLETED
            
            val detailsImg = doc.selectFirst("img.poster, .tvCard img, .movie-poster img")
            val rawDetailsImg = detailsImg?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: detailsImg?.attr("src")
            thumbnail_url = rawDetailsImg?.let {
                if (it.startsWith("http")) it else "$baseUrl/${it.trimStart('/')}"
            }
            
            // Extra info extraction for description
            val year = doc.select("span.chip:matches(\\d{4})").text()
            val duration = doc.select("span.chip:matches(\\d+h \\d+m)").text()
            val lang = doc.select("span.chip:contains(Lang:)").text()
            val score = doc.select("span.pill:contains(User Score:)").text()
            
            var extraInfo = ""
            if (!year.isNullOrBlank()) extraInfo += "\nYear: $year"
            if (!duration.isNullOrBlank()) extraInfo += "\nDuration: $duration"
            if (!lang.isNullOrBlank()) extraInfo += "\n$lang"
            if (!score.isNullOrBlank()) extraInfo += "\n$score"
            
            description = (description + extraInfo).trim()
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
            val id = url.substringAfter("id=")
            episodes.add(SEpisode.create().apply {
                name = "Movie"
                episode_number = 1f
                this.url = "/player.php?id=$id"
            })
        } else if (url.contains("watch.php")) {
            val doc = response.asJsoup()
            val id = if (url.contains("series_id=")) {
                url.substringAfter("series_id=").substringBefore("&")
            } else {
                url.substringAfter("id=").substringBefore("&")
            }
            
            val seasonOptions = doc.select("select[name=season] option")
            val seasons = if (seasonOptions.isNotEmpty()) {
                seasonOptions.map { it.attr("value") }
            } else {
                listOf("1")
            }

            seasons.forEach { season ->
                try {
                    val metaUrl = "$baseUrl/watch.php?id=$id&season=$season&meta=1"
                    val metaResponse = client.newCall(GET(metaUrl, headers)).execute()
                    val metaJson = json.decodeFromString<JsonObject>(metaResponse.body.string())
                    
                    metaJson["episodes"]?.jsonObject?.entries?.mapNotNull { (key, value) ->
                        try {
                            val epJson = value.jsonObject
                            val rawName = epJson["title"]?.jsonPrimitive?.content ?: "Episode $key"
                            val epPath = epJson["path"]?.jsonPrimitive?.content ?: ""
                            
                            var epNum = epJson["episode_number"]?.jsonPrimitive?.content?.toFloatOrNull() 
                                ?: key.toFloatOrNull() 
                                ?: 0f
                            
                            if (epNum == 0f) {
                                val match = Regex("""(?i)E(\d+)""").find(rawName)
                                epNum = match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                            }

                            SEpisode.create().apply {
                                name = if (seasons.size > 1) "S$season $rawName" else rawName
                                episode_number = epNum + (season.toIntOrNull() ?: 0) * 1000f
                                this.url = epPath
                            }
                        } catch (e: Exception) { null }
                    }?.sortedBy { it.episode_number }?.let { episodes.addAll(it) }
                } catch (e: Exception) {}
            }
        }
        return episodes.reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        if (episode.url.startsWith("http")) return GET(episode.url, headers)
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

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Note: Only one category group works at a time"),
        MovieCategoryFilter(),
        TvCategoryFilter(),
        AnimationCategoryFilter(),
        ShowsCategoryFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Filters (Apply to Search)"),
        YearFilter(),
        GenreFilter()
    )

    private abstract class SelectFilter(name: String, values: Array<String>) : AnimeFilter.Select<String>(name, values) {
        fun toUriPart() = if (state == 0) "" else values[state]
    }

    private class MovieCategoryFilter : SelectFilter("Movies", arrayOf(
        "Any", "3D Movies", "4K Movies", "Animation", "Anime", "Bangla", "Bangla Dubbed", "Bangla Movies",
        "Chinese", "Documentaries", "Dual Audio", "English", "Exclusive Full HD", "Foreign",
        "Hindi", "Indonesian", "Japanese", "Kids Cartoon", "Korean", "Pakistani", "Punjabi", "Romance",
        "Hindi Dubbed/Chinees Movies", "Hindi Dubbed/English Movies", "Hindi Dubbed/Indonesian Movies",
        "Hindi Dubbed/Japanese Movies", "Hindi Dubbed/Korean Movies", "Hindi Dubbed/Tamil Movies"
    ))

    private class TvCategoryFilter : SelectFilter("TV Series", arrayOf(
        "Any", "Bangla Series", "Bangla Drama", "Indian Bangla", "Indian Bangla Drama",
        "Web Series", "Hindi Series", "Pakistani Series", "English Series", "Korean Series", "Japanese Series", "Others Series",
        "Islamic Series", "Bangla Dubbed", "Hindi Dubbed", "Bangla"
    ))

    private class AnimationCategoryFilter : SelectFilter("Animation & Cartoon", arrayOf(
        "Any", "Animation Series", "Bangla Animation", "Hindi Animation", "English Animation", "Others Animation"
    ))

    private class ShowsCategoryFilter : SelectFilter("Shows & Others", arrayOf(
        "Any", "Award Shows", "Bangla Shows", "English Shows", "Hindi Shows", "Others Shows",
        "WWE", "AEW Wrestling", "WWE Wrestling", "Entertainment", "Documentary"
    ))
    
    class YearFilter : AnimeFilter.Group<CheckBox>("Year", (2026 downTo 1900).map { CheckBox(it.toString()) })
    class CheckBox(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)
    
    class GenreFilter : AnimeFilter.Group<CheckBox>("Genres", listOf(
        "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", 
        "Drama", "Family", "Fantasy", "Film-Noir", "History", "Horror", "Music", "Musical", 
        "Mystery", "Romance", "Sci-Fi", "Short", "Sport", "Thriller", "War", "Western"
    ).map { CheckBox(it) })

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}