package tv.own.owntv.core.epg

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Smart EPG matching (#13): pairs a channel with a guide channel by *name* when the provider's
 * tvg-id is missing or doesn't line up with the EPG feed's ids.
 *
 * Two pure pieces, both easy to unit-test:
 *  - [normalizeForEpg] strips the cosmetic noise IPTV names carry (quality tags, country brackets,
 *    separators) so "DE| FUSS-TV 3 ᴴᴰ" and "fusstv3.de" reduce to comparable tokens.
 *  - [jaroWinkler] scores two normalized strings 0..1; [bestEpgMatch] picks the top candidate.
 *
 * Nothing here touches the DB or DataStore — callers feed in candidates and persist the winner into
 * the existing `CustomizationStore.epgMatches`, so no new table/migration is needed.
 */
object EpgMatcher {

    /** Score at/above which a match is trustworthy enough to apply automatically. */
    const val AUTO_THRESHOLD = 0.92

    /** Score at/above which a match is worth showing for human review (below = ignored). */
    const val REVIEW_THRESHOLD = 0.74

    /** Loose score at/above which a picker entry counts as "related" and floats to the top —
     *  it only affects ordering (never applies a match), so it can be far below [REVIEW_THRESHOLD]. */
    const val PICKER_SUGGEST_THRESHOLD = 0.55

    // Cosmetic tokens that say nothing about *which* channel this is.
    private val NOISE = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hq", "lq",
        "hevc", "h265", "h264", "fps", "raw", "vip", "backup", "feed", "alt",
        "1080p", "1080", "720p", "720", "480p", "540p", "2160p", "fullhd",
        "multi", "multisub", "latino",
    )

    // Country/region codes IPTV names tag on as a leading/trailing group prefix ("DE| …", "… UK"),
    // plus the spelled-out country names EPG feeds use ("MTV France" ↔ "FR| MTV").
    // Only stripped at the ends (never mid-name), so a real word in the middle is never lost.
    private val COUNTRY = setOf(
        "us", "uk", "ca", "au", "nz", "ie", "za", "in", "pk",
        "de", "at", "ch", "fr", "es", "pt", "it", "nl", "be", "lu",
        "pl", "cz", "sk", "hu", "ro", "bg", "gr", "tr", "ru", "ua",
        "se", "no", "dk", "fi", "is", "ee", "lv", "lt", "hr", "rs", "si",
        "br", "mx", "ar", "cl", "co", "pe", "ve", "ae", "sa", "qa", "eg",
        "usa", "america", "canada", "australia", "ireland", "england", "scotland", "wales",
        "france", "germany", "deutschland", "austria", "switzerland", "spain", "espana",
        "italy", "italia", "portugal", "netherlands", "holland", "belgium",
        "poland", "polska", "czech", "czechia", "slovakia", "hungary", "romania", "bulgaria",
        "greece", "turkey", "turkiye", "russia", "ukraine",
        "sweden", "norway", "denmark", "finland", "iceland", "croatia", "serbia", "slovenia",
        "brazil", "brasil", "mexico", "argentina", "chile", "colombia", "peru", "venezuela",
        "egypt", "arabia", "arabic",
    )

    // Spelled-out channel numbers normalize to digits so "BBC One" and "BBC 1" compare equal.
    private val NUMBER_WORDS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )

    private val BRACKETS = Regex("[\\[(\\{][^\\])}]*[\\])}]")
    private val SEPARATORS = Regex("[._\\-:|/+]")
    private val NON_ALNUM = Regex("[^a-z0-9 ]")
    private val SPACES = Regex("\\s+")

    /**
     * Reduce a raw channel/EPG name to a comparable token string: lowercase, bracketed tags removed,
     * separators flattened to spaces, cosmetic/quality words dropped, non-alphanumerics stripped.
     * e.g. "DE| FUSS-TV 3 [HD]" -> "fuss 3", "FussTV3.de" -> "fusstv3 de" ... then noise filtered.
     */
    /**
     * Memo for [normalizeForEpg]. Normalising is four regex passes plus a token walk, and the picker
     * re-normalises the *same* channel names on every keystroke — measured at 1.9 s to rank 3,958 rows,
     * of which the normalisation is the bulk (`C-F15`). The candidate set repeats heavily across
     * keystrokes, so a bounded cache removes almost all of the repeat cost.
     *
     * Bounded and LRU because the auto-matcher runs this over whole catalogues during sync; an unbounded
     * map would quietly hold every channel name in the app.
     */
    private const val NORMALIZE_CACHE_MAX = 4_096
    private val normalizeCache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > NORMALIZE_CACHE_MAX
    }

    fun normalizeForEpg(raw: String): String {
        synchronized(normalizeCache) { normalizeCache[raw] }?.let { return it }
        val computed = normalizeUncached(raw)
        synchronized(normalizeCache) { normalizeCache[raw] = computed }
        return computed
    }

    private fun normalizeUncached(raw: String): String {
        var s = raw.lowercase()
        s = s.replace(BRACKETS, " ")
        s = s.replace(SEPARATORS, " ")
        s = s.replace(NON_ALNUM, " ")
        val tokens = s.split(SPACES).filter { it.isNotBlank() && it !in NOISE }
            .map { NUMBER_WORDS[it] ?: it }.toMutableList()
        // Drop a country/region tag at either end — but only if a wordy token remains, so channels
        // that ARE a country plus a number ("France 24", "France 2") keep their name.
        fun canDropCountryAt(index: Int) = tokens.size > 1 && tokens[index] in COUNTRY &&
            tokens.filterIndexed { i, _ -> i != index }.any { t -> t.any { it.isLetter() } }
        if (canDropCountryAt(0)) tokens.removeAt(0)
        if (tokens.isNotEmpty() && canDropCountryAt(tokens.size - 1)) tokens.removeAt(tokens.size - 1)
        return tokens.joinToString(" ").trim()
    }

    // ---- Combined similarity: Jaro-Winkler OR token overlap, guarded by channel numbers ----

    private val DIGIT_RUN = Regex("\\d+")

    /** Different channel numbers ("MTV 2" vs "MTV 3") can never even reach review. */
    private const val DIGIT_MISMATCH_CAP = 0.60

    /** A number on only one side ("MTV" vs "MTV 2") stays below auto-apply — review at best. */
    private const val DIGIT_MISSING_CAP = 0.90

    /**
     * Similarity of two already-normalized names (0..1): the better of Jaro-Winkler (typos,
     * concatenated ids like "fusstv3") and token overlap (reordered words, "MTV France HD" vs
     * "France MTV"), then capped when the embedded channel numbers disagree — string similarity
     * alone happily matches "Sky Sports 2" to "Sky Sports 3", which is always wrong.
     */
    fun scoreNormalized(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        var score = maxOf(jaroWinkler(a, b), tokenDice(a, b))
        val da = digitRuns(a)
        val db = digitRuns(b)
        if (da != db) {
            val cap = if (da.isNotEmpty() && db.isNotEmpty()) DIGIT_MISMATCH_CAP else DIGIT_MISSING_CAP
            score = minOf(score, cap)
        }
        return score
    }

    /** Sørensen–Dice over the two token sets — order-insensitive word overlap. */
    private fun tokenDice(a: String, b: String): Double {
        val ta = a.split(' ').toSet()
        val tb = b.split(' ').toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.count { it in tb }
        return 2.0 * inter / (ta.size + tb.size)
    }

    /** Sorted digit runs with leading zeros dropped, so "MTV 02" and "mtv2" both yield ["2"]. */
    private fun digitRuns(s: String): List<String> =
        DIGIT_RUN.findAll(s).map { it.value.trimStart('0').ifEmpty { "0" } }.toList().sorted()

    /**
     * Jaro–Winkler similarity (0..1) — tolerant of typos/transpositions and biased toward common
     * prefixes, which suits channel names well. Operates on already-normalized strings.
     */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val jaro = jaro(a, b)
        // Winkler boost: up to 4 leading chars shared, factor 0.1.
        var prefix = 0
        val max = minOf(4, minOf(a.length, b.length))
        while (prefix < max && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    private fun jaro(a: String, b: String): Double {
        val matchDistance = maxOf(a.length, b.length) / 2 - 1
        val aMatches = BooleanArray(a.length)
        val bMatches = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val start = maxOf(0, i - matchDistance)
            val end = minOf(i + matchDistance + 1, b.length)
            for (j in start until end) {
                if (bMatches[j] || a[i] != b[j]) continue
                aMatches[i] = true
                bMatches[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0

        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatches[i]) continue
            while (!bMatches[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        return (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
    }

    /** A guide channel to match against — its stable id plus optional human display name. */
    data class Candidate(val epgChannelId: String, val displayName: String?)

    /** A [Candidate] with its names pre-normalized, so a bulk scan normalizes each candidate once. */
    data class Prepared(val epgChannelId: String, val displayName: String?, val normName: String, val normId: String)

    /** The chosen guide channel for a source channel, with the confidence that picked it. */
    data class Result(val epgChannelId: String, val displayName: String?, val score: Double)

    /** Pre-normalize a candidate list once before scanning many channels against it. */
    fun prepare(candidates: List<Candidate>): List<Prepared> = candidates.map {
        Prepared(it.epgChannelId, it.displayName, it.displayName?.let(::normalizeForEpg) ?: "", normalizeForEpg(it.epgChannelId))
    }

    /**
     * Best guide candidate for [channelName], or null if nothing clears [minScore]. Each candidate is
     * scored on its display name *and* its id (some feeds only have meaningful ids), best of the two.
     */
    fun bestEpgMatch(
        channelName: String,
        candidates: List<Candidate>,
        minScore: Double = REVIEW_THRESHOLD,
    ): Result? = bestEpgMatchPrepared(channelName, prepare(candidates), minScore)

    /** [bestEpgMatch] over a pre-[prepare]d candidate list — the hot path for bulk auto-matching. */
    fun bestEpgMatchPrepared(
        channelName: String,
        candidates: List<Prepared>,
        minScore: Double = REVIEW_THRESHOLD,
    ): Result? {
        val target = normalizeForEpg(channelName)
        if (target.isEmpty()) return null
        var best: Result? = null
        for (c in candidates) {
            val byName = scoreNormalized(target, c.normName)
            val byId = scoreNormalized(target, c.normId)
            val score = maxOf(byName, byId)
            if (score >= minScore && (best == null || score > best.score)) {
                best = Result(c.epgChannelId, c.displayName, score)
                if (score == 1.0) break // perfect match — can't beat it
            }
        }
        return best
    }

    /**
     * Order picker entries for the manual "Match EPG" dialog: everything scoring at least
     * [PICKER_SUGGEST_THRESHOLD] against [channelName] floats to the top (best first), the rest keep
     * their incoming (alphabetical) order. Ranking only — nothing here applies a match.
     */
    fun <T> rankForPicker(
        channelName: String,
        items: List<T>,
        displayName: (T) -> String?,
        epgChannelId: (T) -> String,
    ): List<T> {
        val target = normalizeForEpg(channelName)
        if (target.isEmpty() || items.isEmpty()) return items
        return order(items, items.map { scoreOne(target, it, displayName, epgChannelId) })
    }

    /**
     * [rankForPicker], scored across all cores.
     *
     * Measured on the owner's TV (`C-F15`): 3,958 candidates took 1.9 s to rank on one thread, which is
     * two seconds of nothing happening after a keystroke. The work is pure CPU over independent rows, so
     * splitting it is free of behaviour change — the scores, the threshold partition and the final order
     * are identical, only the wall clock differs.
     *
     * Below [PARALLEL_MIN_ITEMS] the coroutine overhead outweighs the win, so it stays sequential.
     */
    suspend fun <T> rankForPickerParallel(
        channelName: String,
        items: List<T>,
        displayName: (T) -> String?,
        epgChannelId: (T) -> String,
    ): List<T> {
        val target = normalizeForEpg(channelName)
        if (target.isEmpty() || items.isEmpty()) return items
        if (items.size < PARALLEL_MIN_ITEMS) return rankForPicker(channelName, items, displayName, epgChannelId)
        val workers = kotlin.math.max(2, Runtime.getRuntime().availableProcessors())
        val chunkSize = (items.size + workers - 1) / workers
        val scores = coroutineScope {
            val parts = ArrayList<Deferred<List<Double>>>()
            for (chunk in items.chunked(chunkSize)) {
                parts += async(Dispatchers.Default) {
                    chunk.map { item -> scoreOne(target, item, displayName, epgChannelId) }
                }
            }
            parts.flatMap { it.await() }
        }
        return order(items, scores)
    }

    private fun <T> scoreOne(
        target: String,
        item: T,
        displayName: (T) -> String?,
        epgChannelId: (T) -> String,
    ): Double {
        val byName = displayName(item)?.let { scoreNormalized(target, normalizeForEpg(it)) } ?: 0.0
        val byId = scoreNormalized(target, normalizeForEpg(epgChannelId(item)))
        return maxOf(byName, byId)
    }

    /** Suggested entries (best first), then everything else in its incoming order. */
    private fun <T> order(items: List<T>, scores: List<Double>): List<T> {
        val scored = items.zip(scores)
        val (suggested, rest) = scored.partition { it.second >= PICKER_SUGGEST_THRESHOLD }
        return suggested.sortedByDescending { it.second }.map { it.first } + rest.map { it.first }
    }

    private const val PARALLEL_MIN_ITEMS = 400
}
