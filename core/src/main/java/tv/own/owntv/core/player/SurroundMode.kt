package tv.own.owntv.core.player

/**
 * How the user wants multichannel audio handled. Persisted as a string (see
 * `SettingsRepository.surroundMode`); the legacy `surround_sound` boolean maps onto it.
 *
 * The distinction that matters is **who decodes Dolby/DTS**:
 *
 *  - [STEREO] — we always decode in-app and hand the sink plain 2.0 PCM. Nothing is bitstreamed, so a
 *    TV/soundbar that lies about its capabilities never gets the chance to mis-play anything. Safest,
 *    lowest latency, and the only mode that is correct on 2.0/2.1 speakers (which is most TVs).
 *  - [SURROUND] — the sink's advertised capabilities are used as-is: Dolby/DTS may be passed through
 *    for the TV/receiver to decode, or decoded to multichannel PCM. Right for a real 5.1/7.1 receiver.
 *  - [AUTO] — starts like [SURROUND] but demotes itself to [STEREO] the moment the audio output is
 *    caught misbehaving. The default.
 *
 * The demotion is **not** the mode's job — see [AudioOutputPolicy]. The watchdog runs in all three
 * modes and cannot be switched off, because "no sound at all" is never what the user asked for.
 */
enum class SurroundMode {
    AUTO, STEREO, SURROUND;

    companion object {
        /**
         * Read from the persisted string, falling back to the pre-4.1.7 boolean.
         *
         * A user who never touched the old switch gets [AUTO] (the new, better default); one who
         * explicitly turned it **on** clearly wants multichannel, so gets [SURROUND]; one who
         * explicitly turned it **off** was working around a broken output, so keeps [STEREO].
         */
        fun of(stored: String?, legacyBoolean: Boolean?): SurroundMode {
            stored?.let { s -> entries.firstOrNull { it.name == s }?.let { return it } }
            return when (legacyBoolean) {
                null -> AUTO
                true -> SURROUND
                false -> STEREO
            }
        }
    }
}
