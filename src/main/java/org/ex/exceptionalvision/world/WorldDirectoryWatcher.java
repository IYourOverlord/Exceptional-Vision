package org.ex.exceptionalvision.world;

import org.ex.exceptionalvision.ExceptionalVision;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches a {@code region/} directory for new or modified {@code .mca} files and
 * notifies a callback after a debounce window, so that a burst of writes (e.g. a
 * Chunky pregeneration run) collapses into a single notification per region.
 * See {@code 05_world_data_ingestion.md}.
 */
public final class WorldDirectoryWatcher implements AutoCloseable {

    private static final Duration DEBOUNCE = Duration.ofSeconds(2);

    private final WatchService watchService;
    private final ScheduledExecutorService debounceExecutor;
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread watchThread;

    public WorldDirectoryWatcher() throws IOException {
        this.watchService = FileSystems.getDefault().newWatchService();
        this.debounceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "exceptional-vision-region-debounce");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts watching {@code regionDir}; {@code onRegionChanged} is called (off the watch thread) per settled file. */
    public synchronized void watch(Path regionDir, Consumer<Path> onRegionChanged) throws IOException {
        if (!Files.isDirectory(regionDir)) {
            ExceptionalVision.LOGGER.warn("Cannot watch non-existent region directory: {}", regionDir);
            return;
        }
        regionDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        if (running) {
            return; // single poll loop handles all registered directories via the shared WatchService
        }
        running = true;
        watchThread = new Thread(() -> pollLoop(onRegionChanged), "exceptional-vision-dir-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void pollLoop(Consumer<Path> onRegionChanged) {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }

            Path dir = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Path changedName = ((WatchEvent<Path>) event).context();
                Path changed = dir.resolve(changedName);
                if (!changed.toString().endsWith(".mca")) {
                    continue;
                }
                scheduleDebounced(changed, onRegionChanged);
            }

            if (!key.reset()) {
                ExceptionalVision.LOGGER.warn("Watched directory became inaccessible: {}", dir);
            }
        }
    }

    private void scheduleDebounced(Path mcaFile, Consumer<Path> onRegionChanged) {
        pending.compute(mcaFile, (path, existing) -> {
            if (existing != null) {
                existing.cancel(false);
            }
            return debounceExecutor.schedule(() -> {
                pending.remove(path);
                onRegionChanged.accept(path);
            }, DEBOUNCE.toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    @Override
    public synchronized void close() throws IOException {
        running = false;
        watchService.close();
        debounceExecutor.shutdownNow();
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }
}
