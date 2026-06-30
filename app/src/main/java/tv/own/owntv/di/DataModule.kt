package tv.own.owntv.di

import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.backup.UserDataResolver
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.M3uParser
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.launcher.LauncherLaunchResolver
import tv.own.owntv.core.launcher.LauncherRecommendationPlanner
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.repository.SeriesRepository
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.tv.TvHomeRepository
import tv.own.owntv.core.update.UpdateManager
import tv.own.owntv.core.sync.SyncManager
import tv.own.owntv.core.weather.WeatherRepository
import java.util.concurrent.TimeUnit

/** Networking, parsers, sync engine, and repositories (Phase 5). */
val dataModule = module {
    // Live snapshot of the global proxy. Backs OkHttp's ProxySelector/Authenticator AND mpv's http-proxy,
    // so the proxy can be toggled at runtime without rebuilding the singleton OkHttpClient below.
    single { tv.own.owntv.core.network.ProxyConfigHolder(get<tv.own.owntv.features.settings.data.SettingsRepository>().proxyConfig) }
    single {
        val proxyHolder = get<tv.own.owntv.core.network.ProxyConfigHolder>()
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)  // fast fail on dead host
            .readTimeout(20, TimeUnit.SECONDS)    // detect mid-sync disconnect quickly
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)       // let SyncManager handle retries, not OkHttp
            // Global proxy (Approach 1): a ProxySelector/Authenticator that read the live snapshot, so
            // enabling/disabling the proxy takes effect immediately. Proxy off = DIRECT = exact prior
            // behavior. Credentials are never logged.
            .proxySelector(proxyHolder.proxySelector)
            .proxyAuthenticator(proxyHolder.proxyAuthenticator)
            // Force HTTP/1.1. Several IPTV panels / EPG hosts (and their CDNs) have flaky HTTP/2 stacks
            // that send RST_STREAM(PROTOCOL_ERROR) on large/slow responses — e.g. big EPG XML downloads
            // (#17) — which OkHttp surfaces as "stream was reset: PROTOCOL_ERROR". HTTP/1.1 sidesteps it
            // with no real downside for our mostly-single-stream downloads.
            .protocols(listOf(Protocol.HTTP_1_1))
            // Default a player-style UA for any request that didn't set one (e.g. Coil image loads),
            // since some IPTV panels reject the stock OkHttp UA. Per-source UAs still override this.
            .addInterceptor { chain ->
                val req = chain.request()
                val out = if (req.header("User-Agent").isNullOrBlank()) {
                    req.newBuilder().header("User-Agent", HttpClient.DEFAULT_USER_AGENT).build()
                } else {
                    req
                }
                chain.proceed(out)
            }
            .build()
    }
    single { HttpClient(get()) }
    single { ConnectivityObserver(androidContext()) }
    single { CustomizationStore(androidContext()) }
    single { tv.own.owntv.core.epg.EpgSourceStore(androidContext()) }
    single { tv.own.owntv.core.player.ForceMpvStore(androidContext()) }
    // store, sourceDao, epgRepository
    single { tv.own.owntv.core.epg.EpgMigration(get(), get(), get()) }
    // channelDao, movieDao, seriesDao, db
    single { tv.own.owntv.core.sync.ImportFinalizer(get(), get(), get(), get()) }
    single { M3uParser() }
    single { XtreamClient(get()) }
    single { WeatherRepository(get(), get()) } // Phase 7 — weather chip
    single { SyncManager(androidContext(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // context, channelDao, movieDao, seriesDao, profileDao, favoriteDao, historyDao, progressDao, contentOrderDao
    single { UserDataResolver(androidContext(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // sourceDao, syncManager, userDataResolver
    single { SourceRepository(get(), get(), get()) }
    // epgDao, httpClient, xtreamClient, channelDao, customize, settings, context, db
    single { EpgRepository(get(), get(), get(), get(), get(), get(), androidContext(), get()) }
    // seriesDao, sourceDao, xtreamClient, userDataResolver
    single { SeriesRepository(get(), get(), get(), get()) }
    // sourceDao, movieDao, seriesDao, progressDao
    single { LauncherRecommendationPlanner(get(), get(), get(), get()) }
    // sourceDao, channelDao, movieDao, seriesDao, progressDao
    single { LauncherLaunchResolver(get(), get(), get(), get(), get()) }
    // context, sourceDao, channelDao, movieDao, seriesDao, progressDao, tvProviderProgramDao, customize, settings
    single { TvHomeRepository(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // planner, resolver, tvHomeRepository
    single { LauncherIntegrationRepository(get(), get(), get()) }
    // context, downloadDao, okHttpClient, settings
    single { DownloadManager(androidContext(), get(), get(), get()) }
    // profileDao, sourceDao, settings, customizationStore, userDataResolver, epgSourceStore, tvHomeRepository
    single { BackupManager(get(), get(), get(), get(), get(), get(), get()) }
    // context, okHttpClient — in-app updates from GitHub Releases
    single { UpdateManager(androidContext(), get()) }
}
