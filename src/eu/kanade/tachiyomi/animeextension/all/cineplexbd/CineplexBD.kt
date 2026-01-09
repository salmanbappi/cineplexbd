package eu.kanade.tachiyomi.animeextension.all.cineplexbd

import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search.php?q=$encodedQuery")
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1, .movie-title, title")?.text()?.replace(" — Watch", "") ?: ""
            description = doc.selectFirst("p.leading-relaxed, #synopsis")?.text() ?: ""
            genre = doc.select("div.ganre-wrapper a, .meta-cat").joinToString { it.text() }
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
                this.url = "player.php?id=$id"
            })
        } else if (url.contains("watch.php")) {
            // Series Logic: Use the meta API
            val id = url.substringAfter("id=").substringBefore("&")
            val metaUrl = "$baseUrl/watch.php?id=$id&meta=1"
            val metaResponse = client.newCall(GET(metaUrl, headers)).execute()
            val metaJson = json.decodeFromString<JsonObject>(metaResponse.body.string())
            
            val epsObj = metaJson["episodes"]?.jsonObject
            epsObj?.entries?.forEach { (key, value) ->
                val epJson = value.jsonObject
                episodes.add(SEpisode.create().apply {
                    name = epJson["title"]?.jsonPrimitive?.content ?: "Episode $key"
                    episode_number = epJson["episode_number"]?.jsonPrimitive?.content?.toFloatOrNull() ?: key.toFloatOrNull() ?: 0f
                    // We store the direct path in URL or keep it for videoListParse
                    this.url = epJson["path"]?.jsonPrimitive?.content ?: ""
                })
            }
        }
        
        return episodes.sortedByDescending { it.episode_number }
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        
        // If it's a direct mp4 path from the meta API
        if (url.endsWith(".mp4") || url.endsWith(".mkv")) {
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}