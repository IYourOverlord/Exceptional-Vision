package dev.ev.meshing.queue;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshTaskQueueTest {

    @Test
    void poll_returnsHighestPriorityTaskFirst() {
        MeshTaskQueue<String> queue = new MeshTaskQueue<>(new NoopMetricsRegistry());

        // Lower numeric value = higher priority, per MeshPriority's convention.
        queue.submit(100L, "low-priority");
        queue.submit(1L, "high-priority");

        assertEquals("high-priority", queue.poll());
        assertEquals("low-priority", queue.poll());
    }

    @Test
    void pollNonBlocking_onEmptyQueue_returnsNullImmediately() {
        MeshTaskQueue<String> queue = new MeshTaskQueue<>(new NoopMetricsRegistry());

        assertNull(queue.pollNonBlocking());
    }

    @Test
    void size_reflectsTaskCountAcrossSubmitAndPoll() {
        MeshTaskQueue<String> queue = new MeshTaskQueue<>(new NoopMetricsRegistry());

        assertEquals(0, queue.size());

        queue.submit(5L, "a");
        queue.submit(3L, "b");
        assertEquals(2, queue.size());

        queue.pollNonBlocking();
        assertEquals(1, queue.size());

        queue.pollNonBlocking();
        assertEquals(0, queue.size());
    }

    @Test
    void concurrentProducersAndConsumers_loseNoElements() throws InterruptedException {
        MeshTaskQueue<Integer> queue = new MeshTaskQueue<>(new NoopMetricsRegistry());

        int producerCount = 4;
        int itemsPerProducer = 500;
        int totalItems = producerCount * itemsPerProducer;

        Set<Integer> produced = ConcurrentHashMap.newKeySet();
        Set<Integer> consumed = ConcurrentHashMap.newKeySet();

        AtomicInteger nextId = new AtomicInteger();
        Random random = new Random(42);

        Thread[] producers = new Thread[producerCount];
        for (int p = 0; p < producerCount; p++) {
            producers[p] = new Thread(() -> {
                for (int i = 0; i < itemsPerProducer; i++) {
                    int id = nextId.getAndIncrement();
                    produced.add(id);
                    long priority = random.nextLong(); // may collide, queue must not lose entries
                    queue.submit(priority, id);
                }
            });
        }

        CountDownLatch consumedAll = new CountDownLatch(totalItems);
        int consumerCount = 3;
        Thread[] consumers = new Thread[consumerCount];
        for (int c = 0; c < consumerCount; c++) {
            consumers[c] = new Thread(() -> {
                while (consumed.size() < totalItems) {
                    Integer task = queue.poll();
                    if (consumed.add(task)) {
                        consumedAll.countDown();
                    }
                }
            });
            consumers[c].setDaemon(true);
        }

        for (Thread t : producers) {
            t.start();
        }
        for (Thread t : consumers) {
            t.start();
        }
        for (Thread t : producers) {
            t.join();
        }

        boolean finished = consumedAll.await(30, TimeUnit.SECONDS);
        assertTrue(finished, "consumers must drain all submitted tasks without loss within the timeout");
        assertEquals(produced, consumed, "every produced task id must be consumed exactly once, none lost");
    }

    @Test
    void equalPriorityTasks_areBothEventuallyPolled_noneLost() {
        MeshTaskQueue<String> queue = new MeshTaskQueue<>(new NoopMetricsRegistry());

        queue.submit(7L, "first");
        queue.submit(7L, "second");

        Set<String> polled = Set.of(queue.poll(), queue.poll());
        assertEquals(Set.of("first", "second"), polled,
            "both equal-priority tasks must be retrievable, exact order between them is not asserted");
    }

    @Test
    void nearTierPriority_alwaysOutranksNormalTierPriority() {
        MeshTaskQueue<String> queue = new MeshTaskQueue<>(new NoopMetricsRegistry());

        // Near-tier: MeshPriority.computeWithNearTierCheck produces a large-magnitude negative
        // long (sign bit set) when within NEAR_PLAYER_UNCONDITIONAL_RADIUS_SECTIONS. We don't
        // need the real method here, only its documented output convention: a negative long.
        long nearTierPriority = Long.MIN_VALUE + 12345L;

        // Normal-tier: MeshPriority.compute always produces a non-negative long (bit 63 clear).
        // Use the smallest possible normal-tier value (all fields zero) as the toughest case:
        // even the "best" normal-tier score must still lose to any near-tier value.
        long bestPossibleNormalTierPriority = 0L;

        queue.submit(bestPossibleNormalTierPriority, "normal-tier-best-case");
        queue.submit(nearTierPriority, "near-tier");

        assertEquals("near-tier", queue.poll(),
            "near-tier (negative long) must outrank even the best-case normal-tier (non-negative long) "
                + "value, via plain signed long comparison — no special-casing required in MeshTaskQueue");
        assertEquals("normal-tier-best-case", queue.poll());
    }
}
