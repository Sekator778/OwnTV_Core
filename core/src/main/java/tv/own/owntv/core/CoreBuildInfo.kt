package tv.own.owntv.core

/**
 * The handful of build-time facts core needs from whichever app is hosting it.
 *
 * A library module gets its own `BuildConfig`, in its own package, and it carries none of these:
 * `VERSION_NAME`/`VERSION_CODE` do not exist for a library at all, and `TMDB_EDGE_KEY` and
 * `DEV_TOOLS` are the *app's* build inputs. Every one of them is genuinely per-app — the phone app
 * ships its own version and injects its own edge key — so core takes them rather than baking them in.
 *
 * Assigned once from `Application.onCreate`, before anything reads them. It is a settable holder and
 * not a Koin binding on purpose: `CrashRecorder.install()` runs before `startKoin`, and a crash
 * report without a version number is exactly the report that is useless. Same shape as the other
 * two early hooks bound alongside it.
 */
object CoreBuildInfo {

    /** The host app's `BuildConfig.VERSION_NAME`, e.g. `4.2.3`. */
    var versionName: String = ""

    /** The host app's `BuildConfig.VERSION_CODE`. */
    var versionCode: Int = 0

    /**
     * Shared secret the default metadata Worker's edge rule requires (`x-owntv-key`). Blank is a
     * supported configuration — forks and fresh clones build without the secret and fall back to the
     * unprotected worker base URL.
     */
    var edgeKey: String = ""

    /**
     * The host app's maintainer-only build switch. Core uses it for one Logcat trace and nothing
     * else; every piece of dev-tool *UI* lives in the app, where `BuildConfig.DEV_TOOLS` is still a
     * compile-time constant and R8 still removes the branch.
     */
    var devTools: Boolean = false

    /**
     * The host app's `BuildConfig.DEBUG`. Read by the engine to decide between a hard `error()` and a
     * logged warning, and to turn up mpv's own log level. Defaulting to `false` means a host that
     * forgets to set it gets the release behaviour, which is the safe direction.
     */
    var debug: Boolean = false

    /** The host app's `BuildConfig.DIAGNOSTIC_BUILD` — starts the live diagnostics log switched on. */
    var diagnosticBuild: Boolean = false
}
