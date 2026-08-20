package dev.ev.render.dirty;

import dev.ev.api.SectionPos;
import dev.ev.api.meshing.Quad;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GeometryChangeDeduplicatorTest {

    @Test
    @DisplayName("First recordAndCheckChanged call for a section returns true")
    void testFirstCheckReturnsTrue() {
        GeometryChangeDeduplicator deduplicator = new GeometryChangeDeduplicator();
        SectionPos pos = new SectionPos(0, 10, 20, 30);

        assertTrue(deduplicator.recordAndCheckChanged(pos, 12345L));
    }

    @Test
    @DisplayName("Second recordAndCheckChanged call with same hash returns false (deduplicated)")
    void testSecondCheckWithSameHashReturnsFalse() {
        GeometryChangeDeduplicator deduplicator = new GeometryChangeDeduplicator();
        SectionPos pos = new SectionPos(0, 10, 20, 30);

        assertTrue(deduplicator.recordAndCheckChanged(pos, 12345L));
        assertFalse(deduplicator.recordAndCheckChanged(pos, 12345L));
    }

    @Test
    @DisplayName("Call with different hash returns true (real geometry change)")
    void testCheckWithDifferentHashReturnsTrue() {
        GeometryChangeDeduplicator deduplicator = new GeometryChangeDeduplicator();
        SectionPos pos = new SectionPos(0, 10, 20, 30);

        assertTrue(deduplicator.recordAndCheckChanged(pos, 12345L));
        assertTrue(deduplicator.recordAndCheckChanged(pos, 99999L));
    }

    @Test
    @DisplayName("hashQuads produces value-based identical hash for equal Quad lists created separately")
    void testValueBasedHashQuads() {
        GeometryChangeDeduplicator deduplicator = new GeometryChangeDeduplicator();

        Quad q1 = new Quad(0, 1, 2, 3, 4, 5, 10);
        Quad q2 = new Quad(1, 0, 0, 0, 16, 16, 20);

        Quad q1Copy = new Quad(0, 1, 2, 3, 4, 5, 10);
        Quad q2Copy = new Quad(1, 0, 0, 0, 16, 16, 20);

        List<Quad> listA = List.of(q1, q2);
        List<Quad> listB = List.of(q1Copy, q2Copy);

        long hashA = deduplicator.hashQuads(listA);
        long hashB = deduplicator.hashQuads(listB);

        assertEquals(hashA, hashB);

        // Different content -> different hash
        List<Quad> listDifferent = List.of(q1, new Quad(1, 0, 0, 0, 16, 16, 21));
        assertNotEquals(hashA, deduplicator.hashQuads(listDifferent));
    }

    @Test
    @DisplayName("Concurrent recordAndCheckChanged calls from many threads/sections don't corrupt internal map")
    void testConcurrentAccessDoesNotThrowOrCorruptState() throws InterruptedException {
        // Regression test for a production crash: GeometryChangeDeduplicator is shared
        // across all MeshWorkerPool worker threads (8 by default). Concurrent put()
        // calls into the backing Long2LongOpenHashMap without synchronization raced on
        // internal resize/rehash and threw ArrayIndexOutOfBoundsException. This test
        // reproduces the shared-instance, many-threads, many-distinct-sections shape
        // that triggered it.
        GeometryChangeDeduplicator deduplicator = new GeometryChangeDeduplicator();

        int threadCount = 8;
        int sectionsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < sectionsPerThread; i++) {
                        // Distinct section per (thread, i) so the map genuinely grows
                        // and resizes under concurrent writers, not just re-reads the
                        // same key.
                        SectionPos pos = new SectionPos(0, threadIndex * sectionsPerThread + i, 0, 0);
                        deduplicator.recordAndCheckChanged(pos, i);
                        // Also hammer a couple of shared/overlapping keys across threads
                        // to exercise the get/put race on the *same* entry, not just
                        // distinct-key resize behavior.
                        SectionPos shared = new SectionPos(0, i % 4, 0, 0);
                        deduplicator.recordAndCheckChanged(shared, (long) threadIndex * 31 + i);
                    }
                } catch (Throwable e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads did not finish in time");
        executor.shutdownNow();

        assertEquals(0, failures.get(), "Concurrent access threw an exception (see corrupted-map race)");
    }
}
