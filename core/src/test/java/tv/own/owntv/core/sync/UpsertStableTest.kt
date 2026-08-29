package tv.own.owntv.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 / S1 — `sortOrder` is compared alongside the content hash instead of being folded into it,
 * so a row the provider only *moved* is still rewritten, while an untouched row is still skipped.
 * Folding it into `computeContentHash()` would have changed every stored hash at once and turned the
 * next resync of a 170k-item catalog into a full rewrite.
 *
 * These exercise [SyncSupport.upsertStable] through a fake [ContentAdapter], so no DAO, Context or
 * android.util.Log is involved.
 */
class UpsertStableTest {

    private data class Row(val remoteId: String, val name: String, val sortOrder: Int, val id: Long = 0, val hash: Int = 0)

    private class Recorder {
        val inserted = ArrayList<Row>()
        val updated = ArrayList<Row>()
    }

    private fun adapterFor(rec: Recorder) = ContentAdapter<Row>(
        remoteIdOf = { it.remoteId },
        // Stands in for computeContentHash(): everything except sortOrder.
        hashOf = { it.name.hashCode() },
        sortOrderOf = { it.sortOrder },
        copyWith = { row, id, hash -> row.copy(id = id ?: 0, hash = hash) },
        updateAll = { rec.updated.addAll(it) },
        insertAll = { rec.inserted.addAll(it) },
        remoteIdsForSource = { emptyList() },
        deleteByRemoteIds = { _, _ -> },
        loadHashes = { emptyList() },
    )

    private suspend fun upsert(rows: List<Row>, stored: Map<String, StoredRow>, rec: Recorder): UpsertStats =
        SyncSupport.upsertStable(rows, stored, adapterFor(rec))

    @Test
    fun `row that only moved is updated and counted as moved`() = runBlocking {
        val rec = Recorder()
        val row = Row(remoteId = "a", name = "Alpha", sortOrder = 7)
        val stored = mapOf("a" to StoredRow(id = 42L, contentHash = "Alpha".hashCode(), sortOrder = 3))

        val stats = upsert(listOf(row), stored, rec)

        assertEquals(1, stats.updated)
        assertEquals(1, stats.moved)
        assertEquals(0, stats.skippedUnchanged)
        assertEquals(0, stats.inserted)
        // Keeps its local row id, so favorites/history/resume stay linked.
        assertEquals(42L, rec.updated.single().id)
        assertEquals(7, rec.updated.single().sortOrder)
    }

    @Test
    fun `row identical in content and position is skipped entirely`() = runBlocking {
        val rec = Recorder()
        val row = Row(remoteId = "a", name = "Alpha", sortOrder = 3)
        val stored = mapOf("a" to StoredRow(id = 42L, contentHash = "Alpha".hashCode(), sortOrder = 3))

        val stats = upsert(listOf(row), stored, rec)

        assertEquals(1, stats.skippedUnchanged)
        assertEquals(0, stats.updated)
        assertEquals(0, stats.moved)
        assertTrue(rec.updated.isEmpty())
    }

    @Test
    fun `changed content is updated but not counted as moved`() = runBlocking {
        val rec = Recorder()
        val row = Row(remoteId = "a", name = "Alpha renamed", sortOrder = 3)
        val stored = mapOf("a" to StoredRow(id = 42L, contentHash = "Alpha".hashCode(), sortOrder = 3))

        val stats = upsert(listOf(row), stored, rec)

        assertEquals(1, stats.updated)
        assertEquals(0, stats.moved)
        assertEquals(42L, rec.updated.single().id)
    }

    @Test
    fun `unknown remote id is inserted`() = runBlocking {
        val rec = Recorder()
        val stats = upsert(listOf(Row(remoteId = "new", name = "New", sortOrder = 0)), emptyMap(), rec)

        assertEquals(1, stats.inserted)
        assertEquals(0, stats.updated)
        assertEquals(0L, rec.inserted.single().id)
    }

    @Test
    fun `a provider reorder rewrites only the rows that actually moved`() = runBlocking {
        val rec = Recorder()
        // Positions 0 and 1 swapped; "c" stayed put.
        val rows = listOf(
            Row(remoteId = "a", name = "A", sortOrder = 1),
            Row(remoteId = "b", name = "B", sortOrder = 0),
            Row(remoteId = "c", name = "C", sortOrder = 2),
        )
        val stored = mapOf(
            "a" to StoredRow(1L, "A".hashCode(), 0),
            "b" to StoredRow(2L, "B".hashCode(), 1),
            "c" to StoredRow(3L, "C".hashCode(), 2),
        )

        val stats = upsert(rows, stored, rec)

        assertEquals(2, stats.moved)
        assertEquals(2, stats.updated)
        assertEquals(1, stats.skippedUnchanged)
        assertEquals(setOf(1L, 2L), rec.updated.map { it.id }.toSet())
    }
}
