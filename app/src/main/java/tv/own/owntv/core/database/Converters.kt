package tv.own.owntv.core.database

import androidx.room.TypeConverter
import tv.own.owntv.core.model.DownloadStatus
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.tv.TvProviderSurface

/** Stores the app's enums as their stable names (survives reordering). */
class Converters {
    @TypeConverter fun mediaTypeToString(v: MediaType): String = v.name
    @TypeConverter fun stringToMediaType(v: String): MediaType = MediaType.valueOf(v)

    @TypeConverter fun sourceTypeToString(v: SourceType): String = v.name
    @TypeConverter fun stringToSourceType(v: String): SourceType = SourceType.valueOf(v)

    @TypeConverter fun downloadStatusToString(v: DownloadStatus): String = v.name
    @TypeConverter fun stringToDownloadStatus(v: String): DownloadStatus = DownloadStatus.valueOf(v)

    @TypeConverter fun tvProviderSurfaceToString(v: TvProviderSurface): String = v.name
    @TypeConverter fun stringToTvProviderSurface(v: String): TvProviderSurface = TvProviderSurface.valueOf(v)
}
