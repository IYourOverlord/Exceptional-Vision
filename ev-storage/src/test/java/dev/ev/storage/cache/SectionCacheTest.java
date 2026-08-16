package dev.ev.storage.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ev.api.SectionPos;
import dev.ev.api.storage.WorldSectionHandle;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SectionCacheTest {

    private static SectionCache newCache(SectionLoader loader) {
        return new SectionCache(4, loader, new LruEvictionPolicy(), new NoopMetricsRegistry());
    }

    private static long pos(int x, int y, int z) {
        return new SectionPos(0, x, y, z).encode();
    }

    @Test
    void acquire_newPosition_createsViaLoader_refCountOne() {
        SectionCache cache = newCache(new FakeSectionLoader());
        long p = pos(1, 2, 3);

        WorldSectionHandle handle = cache.acquire(p, false);

        assertNotNull(handle);
        assertEquals(1, handle.refCount());
    }

    @Test
    void acquire_samePositionTwice_returnsSameInstance_refCountIncreases() {
        SectionCache cache = newCache(new FakeSectionLoader());
        long p = pos(5, 5, 5);

        WorldSectionHandle first = cache.acquire(p, false);
        WorldSectionHandle second = cache.acquire(p, false);

        assertSame(first, second);
        assertEquals(2, second.refCount());
    }

    @Test
    void acquire_onlyIfExists_missingPosition_returnsNullWithoutCreating() {
        FakeSectionLoader loader = new FakeSectionLoader(); // nothing exists on "disk"
        SectionCache cache = newCache(loader);
        long p = pos(9, 9, 9);

        WorldSectionHandle handle = cache.acquire(p, true);

        assertNull(handle);
        assertEquals(0, loader.loadCount());
        assertEquals(0, cache.activeCount());
    }

    @Test
    void acquire_onlyIfExists_presentPosition_loadsAndReturns() {
        long p = pos(2, 2, 2);
        FakeSectionLoader loader = new FakeSectionLoader(Set.of(p));
        SectionCache cache = newCache(loader);

        WorldSectionHandle handle = cache.acquire(p, true);

        assertNotNull(handle);
        assertEquals(1, handle.refCount());
    }

    @Test
    void activeCount_reflectsUniqueCachedSections() {
        SectionCache cache = newCache(new FakeSectionLoader());

        cache.acquire(pos(0, 0, 0), false);
        cache.acquire(pos(1, 0, 0), false);
        cache.acquire(pos(0, 0, 0), false); // repeat, same section

        assertEquals(2, cache.activeCount());
    }

    @Test
    void concurrentAcquire_manyThreadsManyPositions_isConsistent() throws InterruptedException {
        int threadCount = 16;
        int positionCount = 64;
        int acquiresPerThread = 200;

        SectionCache cache = newCache(new FakeSectionLoader());
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger[] expectedRefCounts = new AtomicInteger[positionCount];
        for (int i = 0; i < positionCount; i++) {
            expectedRefCounts[i] = new AtomicInteger(0);
        }

        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    java.util.Random rnd = new java.util.Random();
                    for (int i = 0; i < acquiresPerThread; i++) {
                        int idx = rnd.nextInt(positionCount);
                        long p = pos(idx, 0, 0);
                        WorldSectionHandle h = cache.acquire(p, false);
                        assertNotNull(h);
                        expectedRefCounts[idx].incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(finished, "concurrent acquire test threads did not finish in time");
        assertEquals(positionCount, cache.activeCount());

        for (int idx = 0; idx < positionCount; idx++) {
            WorldSectionHandle h = cache.acquire(pos(idx, 0, 0), false);
            // +1 here because this final acquire itself bumps the ref count by one more.
            assertEquals(expectedRefCounts[idx].get() + 1, h.refCount(),
                    "ref count mismatch for position index " + idx);
        }
    }

    @Test
    void assertEmpty_throwsWhenNotEmpty() {
        SectionCache cache = newCache(new FakeSectionLoader());
        cache.acquire(pos(0, 0, 0), false);

        assertThrows(IllegalStateException.class, cache::assertEmpty);
    }

    @Test
    void assertEmpty_doesNotThrowWhenEmpty() {
        SectionCache cache = newCache(new FakeSectionLoader());
        cache.assertEmpty(); // must not throw
        assertFalse(false); // reached without exception
    }
}
