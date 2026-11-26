package space.abhijithkm.shoppinglist.surfaces

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceUpdaterTest {
    private class CountingRefresher(private val failFirst: Boolean = false) : SurfaceRefresher {
        var count = 0
        override suspend fun refreshAll() {
            count++
            if (failFirst && count == 1) error("boom")
        }
    }

    private val changes = MutableSharedFlow<Unit>()
    private val source = ChangeSource { changes }

    @Test
    fun burstOfTwentyWritesRefreshesOnce() = runTest {
        val refresher = CountingRefresher()
        SurfaceUpdater(source, refresher).start(backgroundScope, debounceMs = 500)
        runCurrent()

        repeat(20) { changes.emit(Unit) }
        advanceTimeBy(600) // advanceUntilIdle() does not wait for work in backgroundScope
        runCurrent()

        assertEquals(1, refresher.count)
    }

    @Test
    fun refreshRunsAfterTheDebounceNotBefore() = runTest {
        val refresher = CountingRefresher()
        SurfaceUpdater(source, refresher).start(backgroundScope, debounceMs = 500)
        runCurrent()

        changes.emit(Unit)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(0, refresher.count)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, refresher.count)
    }

    @Test
    fun aFailingRefreshIsReportedAndDoesNotStopTheCollection() = runTest {
        val refresher = CountingRefresher(failFirst = true)
        val errors = mutableListOf<Throwable>()
        SurfaceUpdater(source, refresher).start(backgroundScope, debounceMs = 500) { errors += it }
        runCurrent()

        changes.emit(Unit)
        advanceTimeBy(600)
        runCurrent()
        changes.emit(Unit)
        advanceTimeBy(600)
        runCurrent()

        assertEquals(2, refresher.count)
        assertEquals(1, errors.size)
    }
}
