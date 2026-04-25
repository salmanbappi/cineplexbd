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
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CineplexBD : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Cineplex BD"
    override val baseUrl = "http://cineplexbd.net"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 5181466391484419848L

    private val json: Json by lazy { Injekt.get() }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "$baseUrl/")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/search.php?q=&year[]=2026&year[]=2025&page=$page")
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/search.php?q=&page=$page")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val hasSearchFilter = filters.any { 
            (it is YearFilter && it.state.any { y -> y.state }) || 
            (it is GenreFilter && it.state.any { g -> g.state })
        }

        if (query.isNotBlank() || hasSearchFilter) {
            val url = "$baseUrl/search.php".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is YearFilter -> {
                        filter.state.forEach { year ->
                            if (year.state) {
                                url.addQueryParameter("year[]", year.name)
                            }
                        }
                    }
                    is GenreFilter -> {
                        filter.state.forEach { genre ->
                            if (genre.state) {
                                url.addQueryParameter("genre[]", genre.name)
                            }
                        }
                    }
                    else -> {}
                }
            }
            return GET(url.build().toString())
        }

        filters.forEach { filter ->
            when (filter) {
                is MovieCategoryFilter -> {
                    if (filter.state != 0) {
                        val url = "$baseUrl/category.php".toHttpUrlOrNull()!!.newBuilder()
                            .addQueryParameter("category", filter.toUriPart())
                            .addQueryParameter("page", page.toString())
                        return GET(url.build().toString())
                    }
                }
                is TvCategoryFilter,\n                is AnimationCategoryFilter,\n                is ShowsCategoryFilter -> {
                    if ((filter as SelectFilter).state != 0) {
                        val url = "$baseUrl/tcategory.php".toHttpUrlOrNull()!!.newBuilder()
                            .addQueryParameter("category", filter.toUriPart())
                            .addQueryParameter("page", page.toString())
                        return GET(url.build().toString())
                    }
                }
                else -> {}
            }
        }

        return popularAnimeRequest(page)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {\n        val page = searchAnimeParse(response)\n        val filtered = page.animes.filterNot { \n            val title = it.title.lowercase()\n            val genre = it.genre?.lowercase() ?: \"\"\n            title.contains(\"bangla\") || genre.contains(\"bangla\") ||\n            title.contains(\"pakistani\") || genre.contains(\"pakistani\")\n        }\n        return AnimesPage(filtered, page.hasNextPage)\n    }\n\n    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)\n\n    override fun searchAnimeParse(response: Response): AnimesPage {\n        val doc = response.asJsoup()\n        val animeList = mutableListOf<SAnime>()\n        \n        doc.select(\"a[href*='view.php'], a[href*='watch.php'], a[href*='tview.php'], .movie-card a, a:has(.poster), a:has(img[src*='uploads'])\").forEach { element ->\n            val item = parseAnimeItem(element)\n            if (item.title != \"Unknown Title\") {\n                animeList.add(item)\n            }\n        }\n        \n        val hasNextPage = doc.select(\"ul.pagination li.active + li a, a:contains(Next), a:contains(»), a.next\").isNotEmpty()\n        return AnimesPage(animeList.distinctBy { it.url }, hasNextPage)\n    }\n\n    private fun parseAnimeItem(element: Element): SAnime {\n        return SAnime.create().apply {\n            val rawUrl = element.attr(\"href\")\n            \n            // Extract ID carefully\n            val id = if (rawUrl.contains(\"series_id=\")) {\n                rawUrl.substringAfter(\"series_id=\").substringBefore(\"&\")\n            } else {\n                rawUrl.substringAfter(\"id=\").substringBefore(\"&\")\n            }\n\n            url = when {\n                rawUrl.contains(\"series_id=\") -> \"/watch.php?series_id=$id\"\n                rawUrl.contains(\"tview.php\") -> \"/tview.php?id=$id\"\n                rawUrl.contains(\"watch.php\") -> \"/watch.php?id=$id\"\n                else -> \"/view.php?id=$id\"\n            }\n\n            val titleEl = element.selectFirst(\"span.truncate, div.text-sm, div.cp-title, h2, .card-title, .title\")\n            val posterImg = element.selectFirst(\"img.poster, .tvCard img, img[class*='poster'], img[src*='uploads/']\")\n            title = titleEl?.text() ?: posterImg?.attr(\"alt\") ?: \"Unknown Title\"\n            \n            // Extract info for filtering (Generic selector for robustness)\n            genre = element.selectFirst(\"p\")?.text()\n\n            var rawImg = posterImg?.attr(\"data-src\")?.takeIf { it.isNotEmpty() }\n                ?: posterImg?.attr(\"src\")\n                ?: element.selectFirst(\"img\")?.attr(\"data-src\")\n                ?: element.selectFirst(\"img\")?.attr(\"src\")\n            \n            if (rawImg.isNullOrEmpty()) {\n                 val style = element.selectFirst(\"div[style*='background-image']\")?.attr(\"style\")\n                 if (style != null && style.contains(\"url(\")) {\n                     rawImg = style.substringAfter(\"url(\").substringBefore(\")\")\n                         .replace(\"'\", \"\").replace(\"\\\"\", \"\")\n                 }\n            }\n\n            thumbnail_url = rawImg?.let {\n                if (it.startsWith(\"http\")) it else \"$baseUrl/${it.trimStart('/')}\"\n            }\n        }\n    }\n\n    override fun animeDetailsRequest(anime: SAnime): Request {\n        val url = if (anime.url.startsWith(\"/\")) anime.url else \"/${anime.url}\"\n        return GET(baseUrl + url, headers)\n    }\n\n    override fun animeDetailsParse(response: Response): SAnime {\n        val doc = response.asJsoup()\n        return SAnime.create().apply {\n            val rawTitle = doc.selectFirst(\"h1, .movie-title, title\")?.text()?.replace(\" — Watch\", \"\") ?: \"\"\n            title = rawTitle\n            description = doc.selectFirst(\"p.leading-relaxed, #synopsis, .description\")?.text() ?: \"\"\n            \n            // Metadata parsing\n            genre = doc.select(\"span.chip:contains(,)\").text().trim() // e.g., \"Drama, Family\"\n            if (genre.isNullOrBlank()) {\n                 genre = doc.select(\"div.ganre-wrapper a, .meta-cat, .genre a\").joinToString { it.text() }\n            }\n            \n            // Strict author parsing to avoid metadata\n            author = doc.select(\"div.mt-4.text-sm:contains(Director:) span\").text() ?: \n                     doc.select(\"a[href*='cast.php'][href*='Director']\").text()\n\n            // Status is generally Completed for movies\n            status = SAnime.COMPLETED\n            \n            val detailsImg = doc.selectFirst(\"img.poster, .tvCard img, .movie-poster img\")\n            val rawDetailsImg = detailsImg?.attr(\"data-src\")?.takeIf { it.isNotEmpty() } ?: detailsImg?.attr(\"src\")\n            thumbnail_url = rawDetailsImg?.let {\n                if (it.startsWith(\"http\")) it else \"$baseUrl/${it.trimStart('/')}\"\n            }\n            \n            // Extra info extraction for description\n            val year = doc.select(\"span.chip:matches(\\\\d{4})\").text()\n            val duration = doc.select(\"span.chip:matches(\\\\d+h \\\\d+m)\").text()\n            val lang = doc.select(\"span.chip:contains(Lang:)\").text()\n            val score = doc.select(\"span.pill:contains(User Score:)\").text()\n            \n            var extraInfo = \"\"\n            if (!year.isNullOrBlank()) extraInfo += \"\\nYear: $year\"\n            if (!duration.isNullOrBlank()) extraInfo += \"\\nDuration: $duration\"\n            if (!lang.isNullOrBlank()) extraInfo += \"\\n$lang\"\n            if (!score.isNullOrBlank()) extraInfo += \"\\n$score\"\n            \n            description = (description + extraInfo).trim()\n        }\n    }\n\n    override fun episodeListRequest(anime: SAnime): Request {\n        val url = if (anime.url.startsWith(\"/\")) anime.url else \"/${anime.url}\"\n        return GET(baseUrl + url, headers)\n    }\n\n    override fun episodeListParse(response: Response): List<SEpisode> {\n        val url = response.request.url.toString()\n        val episodes = mutableListOf<SEpisode>()\n\n        if (url.contains(\"view.php\") || url.contains(\"tview.php\")) {\n            val id = url.substringAfter(\"id=\").substringBefore(\"&\")\n            episodes.add(SEpisode.create().apply {\n                name = \"Movie\"\n                episode_number = 1f\n                this.url = \"/player.php?id=$id\"\n            })\n        } else if (url.contains(\"watch.php\")) {\n            val doc = response.asJsoup()\n            val id = if (url.contains(\"series_id=\")) {\n                url.substringAfter(\"series_id=\").substringBefore(\"&\")\n            } else {\n                url.substringAfter(\"id=\").substringBefore(\"&\")\n            }\n            \n            val seasonOptions = doc.select(\"select[name=season] option\")\n            val seasons = if (seasonOptions.isNotEmpty()) {\n                seasonOptions.map { it.attr(\"value\") }\n            } else {\n                listOf(\"1\")\n            }\n\n            seasons.forEach { season ->\n                try {\n                    val metaUrl = \"$baseUrl/watch.php?id=$id&season=$season&meta=1\"\n                    val metaResponse = client.newCall(GET(metaUrl, headers)).execute()\n                    val metaJson = json.decodeFromString<JsonObject>(metaResponse.body.string())\n                    \n                    metaJson[\"episodes\"]?.jsonObject?.entries?.mapNotNull { (key, value) ->\n                        try {\n                            val epJson = value.jsonObject\n                            val rawName = epJson[\"title\"]?.jsonPrimitive?.content ?: \"Episode $key\"\n                            val epPath = epJson[\"path\"]?.jsonPrimitive?.content ?: \"\"\n                            \n                            var epNum = epJson[\"episode_number\"]?.jsonPrimitive?.content?.toFloatOrNull() \n                                ?: key.toFloatOrNull() \n                                ?: 0f\n                            \n                            if (epNum == 0f) {\n                                val match = Regex(\"\"\"(?i)E(\\d+)\"\"\").find(rawName)\n                                epNum = match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f\n                            }\n\n                            SEpisode.create().apply {\n                                name = if (seasons.size > 1) \"S$season $rawName\" else rawName\n                                episode_number = epNum + (season.toIntOrNull() ?: 0) * 1000f\n                                this.url = epPath\n                            }\n                        } catch (e: Exception) { null }\n                    }?.sortedBy { it.episode_number }?.let { episodes.addAll(it) }\n                } catch (e: Exception) {}\n            }\n        }\n        return episodes.reversed()\n    }\n\n    override fun videoListRequest(episode: SEpisode): Request {\n        val videoHeaders = headers.newBuilder()\n            .add(\"Referer\", \"$baseUrl/\")\n            .build()\n        if (episode.url.startsWith(\"http\")) return GET(episode.url, videoHeaders)\n        val url = if (episode.url.startsWith(\"/\")) episode.url else \"/${episode.url}\"\n        return GET(baseUrl + url, videoHeaders)\n    }\n\n    override fun videoListParse(response: Response): List<Video> {\n        val requestUrl = response.request.url.toString()\n        val videoHeaders = headers.newBuilder()\n            .add(\"Referer\", \"$baseUrl/\")\n            .build()\n\n        if (requestUrl.endsWith(\".mp4\") || requestUrl.endsWith(\".mkv\") || requestUrl.contains(\"/Data/\")) {\n            return listOf(Video(requestUrl, \"Direct\", requestUrl, headers = videoHeaders))\n        }\n\n        val html = response.body.string()\n        val videoList = mutableListOf<Video>()\n        \n        // Try regex first (modern player style)\n        val videoUrl = Regex(\"\"\"const videoSrc\\s*=\\s*[\"'](.*?)[\"']\"\"\").find(html)?.groupValues?.get(1)\n        \n        if (!videoUrl.isNullOrBlank()) {\n            val finalUrl = if (videoUrl.startsWith(\"http\")) videoUrl else \"$baseUrl/${videoUrl.trimStart('/')}\"\n            if (finalUrl.contains(\".m3u8\")) {\n                // HLS Playlist\n                videoList.add(Video(finalUrl, \"HLS (Playlist)\", finalUrl, headers = videoHeaders))\n                \n                // Try to get a more direct link if it's a master playlist to avoid 0MB downloads\n                try {\n                    val m3u8Response = client.newCall(GET(finalUrl, videoHeaders)).execute()\n                    val m3u8Content = m3u8Response.body.string()\n                    if (m3u8Content.contains(\"#EXT-X-STREAM-INF\")) {\n                        // It's a master playlist, find the first quality sub-playlist\n                        val lines = m3u8Content.split(\"\\n\")\n                        val subUrl = lines.firstOrNull { it.isNotBlank() && !it.startsWith(\"#\") } ?: \n                                     lines.getOrNull(lines.indexOfFirst { it.contains(\"#EXT-X-STREAM-INF\") } + 1)\n\n                        if (!subUrl.isNullOrBlank() && !subUrl.startsWith(\"#\")) {\n                            val absoluteSubUrl = if (subUrl.startsWith(\"http\")) subUrl else {\n                                finalUrl.substringBeforeLast(\"/\") + \"/\" + subUrl.trim()\n                            }\n                            videoList.add(Video(absoluteSubUrl, \"HLS (Direct)\", absoluteSubUrl, headers = videoHeaders))\n                        }\n                    }\n                } catch (e: Exception) {}\n            } else {\n                videoList.add(Video(finalUrl, \"Original\", finalUrl, headers = videoHeaders))\n            }\n        }\n        \n        // Fallback to Jsoup (legacy/other pages or direct tags)\n        Jsoup.parse(html).select(\"source[src*='.mp4'], source[src*='.mkv'], source[src*='.m3u8'], source\").forEach { \n            val src = it.attr(\"src\")\n            if (src.isNotBlank() && src != videoUrl) {\n                val finalUrl = if (src.startsWith(\"http\")) src else \"$baseUrl/${src.trimStart('/')}\"\n                val quality = when {\n                    finalUrl.contains(\".m3u8\") -> \"HLS Fallback\"\n                    else -> \"Direct Fallback\"\n                }\n                videoList.add(Video(finalUrl, quality, finalUrl, headers = videoHeaders))\n            }\n        }\n\n        return videoList.distinctBy { it.videoUrl }\n    }\n\n    override fun getFilterList() = AnimeFilterList(\n        AnimeFilter.Header(\"Note: Only one category group works at a time\"),\n        MovieCategoryFilter(),\n        TvCategoryFilter(),\n        AnimationCategoryFilter(),\n        ShowsCategoryFilter(),\n        AnimeFilter.Separator(),\n        AnimeFilter.Header(\"Filters (Apply to Search)\"),\n        YearFilter(),\n        GenreFilter()\n    )\n\n    private abstract class SelectFilter(name: String, values: Array<String>) : AnimeFilter.Select<String>(name, values) {\n        fun toUriPart() = if (state == 0) \"\" else values[state]\n    }\n\n    private class MovieCategoryFilter : SelectFilter(\"Movies\", arrayOf(\n        \"Any\", \"3D Movies\", \"4K Movies\", \"Animation\", \"Anime\", \"Bangla\", \"Bangla Dubbed\", \"Bangla Movies\",\n        \"Chinese\", \"Documentaries\", \"Dual Audio\", \"English\", \"Exclusive Full HD\", \"Foreign\",\n        \"Hindi\", \"Indonesian\", \"Japanese\", \"Kids Cartoon\", \"Korean\", \"Pakistani\", \"Punjabi\", \"Romance\",\n        \"Hindi Dubbed/Chinees Movies\", \"Hindi Dubbed/English Movies\", \"Hindi Dubbed/Indonesian Movies\",\n        \"Hindi Dubbed/Japanese Movies\", \"Hindi Dubbed/Korean Movies\", \"Hindi Dubbed/Tamil Movies\"\n    ))\n\n    private class TvCategoryFilter : SelectFilter(\"TV Series\", arrayOf(\n        \"Any\", \"Bangla Series\", \"Bangla Drama\", \"Indian Bangla\", \"Indian Bangla Drama\",\n        \"Web Series\", \"Hindi Series\", \"Pakistani Series\", \"English Series\", \"Korean Series\", \"Japanese Series\", \"Others Series\",\n        \"Islamic Series\", \"Bangla Dubbed\", \"Hindi Dubbed\", \"Bangla\"\n    ))\n\n    private class AnimationCategoryFilter : SelectFilter(\"Animation & Cartoon\", arrayOf(\n        \"Any\", \"Animation Series\", \"Bangla Animation\", \"Hindi Animation\", \"English Animation\", \"Others Animation\"\n    ))\n\n    private class ShowsCategoryFilter : SelectFilter(\"Shows & Others\", arrayOf(\n        \"Any\", \"Award Shows\", \"Bangla Shows\", \"English Shows\", \"Hindi Shows\", \"Others Shows\",\n        \"WWE\", \"AEW Wrestling\", \"WWE Wrestling\", \"Entertainment\", \"Documentary\"\n    ))\n    \n    class YearFilter : AnimeFilter.Group<AnimeFilter.CheckBox>(\"Year\", (2026 downTo 1900).map { MyCheckBox(it.toString()) })\n    class MyCheckBox(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)\n    \n    class GenreFilter : AnimeFilter.Group<AnimeFilter.CheckBox>(\"Genres\", listOf(\n        \"Action\", \"Adventure\", \"Animation\", \"Biography\", \"Comedy\", \"Crime\", \"Documentary\", \n        \"Drama\", \"Family\", \"Fantasy\", \"Film-Noir\", \"History\", \"Horror\", \"Music\", \"Musical\", \n        \"Mystery\", \"Romance\", \"Sci-Fi\", \"Short\", \"Sport\", \"Thriller\", \"War\", \"Western\"\n    ).map { MyCheckBox(it) })\n\n    override fun setupPreferenceScreen(screen: PreferenceScreen) {}\n}