package tv.own.owntv.core.parser

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.PushbackInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Streaming XMLTV guide parser. XMLTV files are large (and often gzipped), so this pulls events with
 * [XmlPullParser] and pushes each `<channel>` / `<programme>` to a callback instead of building a
 * tree — the caller stores them in chunks and filters to a time window.
 */
object XmltvParser {

    /**
     * Parse an XMLTV stream. [onChannel] gets (id, displayName); [onProgramme] gets
     * (channelId, startMs, stopMs, title, description). Gzip is detected from the magic bytes.
     */
    fun parse(
        input: InputStream,
        onChannel: (id: String, displayName: String?) -> Unit,
        onProgramme: (channelId: String, startMs: Long, stopMs: Long, title: String, description: String?) -> Unit,
    ) {
        val stream = maybeGunzip(input)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser, onChannel)
                    "programme" -> readProgramme(parser, onProgramme)
                }
            }
            event = parser.next()
        }
    }

    private fun readChannel(parser: XmlPullParser, onChannel: (String, String?) -> Unit) {
        val id = parser.getAttributeValue(null, "id").orEmpty()
        var displayName: String? = null
        while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "channel")) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "display-name" && displayName == null) {
                displayName = parser.nextText().trim().takeIf { it.isNotBlank() }
            }
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
        }
        if (id.isNotBlank()) onChannel(id, displayName)
    }

    private fun readProgramme(parser: XmlPullParser, onProgramme: (String, Long, Long, String, String?) -> Unit) {
        val channelId = parser.getAttributeValue(null, "channel").orEmpty()
        val startMs = parseTime(parser.getAttributeValue(null, "start"))
        val stopMs = parseTime(parser.getAttributeValue(null, "stop"))
        var title = ""
        var desc: String? = null
        while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "programme")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> if (title.isBlank()) title = parser.nextText().trim()
                    "desc" -> if (desc == null) desc = parser.nextText().trim().takeIf { it.isNotBlank() }
                }
            }
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
        }
        if (channelId.isNotBlank() && startMs > 0 && stopMs > startMs) {
            onProgramme(channelId, startMs, stopMs, title.ifBlank { "—" }, desc)
        }
    }

    /** XMLTV time is `yyyyMMddHHmmss` optionally followed by a ` +0000` style offset. */
    private fun parseTime(raw: String?): Long {
        val t = raw?.trim()?.replace(" ", "") ?: return 0
        if (t.length < 14) return 0
        return try {
            if (t.length >= 15 && (t[14] == '+' || t[14] == '-')) {
                SimpleDateFormat("yyyyMMddHHmmssZ", Locale.US).parse(t)?.time ?: 0
            } else {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(t.take(14))?.time ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val pb = PushbackInputStream(input, 2)
        val sig = ByteArray(2)
        val n = pb.read(sig, 0, 2)
        if (n > 0) pb.unread(sig, 0, n)
        val gzipped = n == 2 && sig[0] == 0x1f.toByte() && sig[1] == 0x8b.toByte()
        return if (gzipped) GZIPInputStream(pb) else pb
    }
}
