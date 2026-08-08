package tv.own.owntv.core.sync.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sync.SyncContentTypes

class TrendingRefreshSchedulingTest {
    private val allVod = SyncContentTypes(live = true, movies = true, series = true)

    @Test
    fun providerModeNeverSchedules() {
        assertFalse(schedule(effective = allVod, metadataEnabled = false))
    }

    @Test
    fun hiddenTrendingRowNeverSchedules() {
        assertFalse(schedule(effective = allVod, trendingVisible = false))
    }

    @Test
    fun liveOnlySuccessfulPassDoesNotSchedule() {
        assertFalse(schedule(effective = SyncContentTypes(live = true, movies = false, series = false)))
    }

    @Test
    fun movieOrSeriesSuccessfulPassSchedules() {
        assertTrue(schedule(effective = SyncContentTypes(live = false, movies = true, series = false)))
        assertTrue(schedule(effective = SyncContentTypes(live = false, movies = false, series = true)))
    }

    @Test
    fun incompletePriorityFirstPassDoesNotSchedule() {
        assertFalse(
            schedule(
                sourceWasNeverSynced = true,
                completesInitialSync = false,
                effective = SyncContentTypes(live = true, movies = false, series = false),
                enabledScope = allVod,
            ),
        )
    }

    @Test
    fun stagedCompletionSchedulesEvenWhenFinalPassIsLiveOnly() {
        assertTrue(
            schedule(
                sourceWasNeverSynced = true,
                completesInitialSync = true,
                effective = SyncContentTypes(live = true, movies = false, series = false),
                enabledScope = allVod,
            ),
        )
    }

    private fun schedule(
        sourceWasNeverSynced: Boolean = false,
        completesInitialSync: Boolean = false,
        effective: SyncContentTypes,
        enabledScope: SyncContentTypes = allVod,
        metadataEnabled: Boolean = true,
        trendingVisible: Boolean = true,
    ) = shouldScheduleTrendingRefresh(
        sourceWasNeverSynced,
        completesInitialSync,
        effective,
        enabledScope,
        metadataEnabled,
        trendingVisible,
    )
}
