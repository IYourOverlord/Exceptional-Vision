package org.ex.exceptionalvision.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * Low-level file helpers shared by {@link LodCacheWriter}, {@link LodCacheLoader} and
 * {@link CacheCompactor}: atomic replace-on-write (so a crash mid-write never leaves a
 * half-written {@code nodes.bin}/{@code quads.bin}/{@code index.json} that would be
 * silently trusted on the next load) and read-only memory-mapping.
 */
final class CacheIo {

    private CacheIo() {
    }

    /**
     * Writes {@code contents} to a {@code .tmp} sibling of {@code target} and then
     * atomically (where the filesystem supports it) moves it into place, so readers
     * never observe a partially written file.
     */
    static void writeAtomic(Path target, Consumer<FileChannel> contents) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            contents.accept(channel);
            channel.force(true);
        } catch (RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw unwrapIo(e);
        }
        moveAtomicallyIfPossible(tmp, target);
    }

    static void writeAtomic(Path target, byte[] bytes) throws IOException {
        writeAtomic(target, channel -> {
            try {
                channel.write(ByteBuffer.wrap(bytes));
            } catch (IOException e) {
                throw new UncheckedIoException(e);
            }
        });
    }

    // FIX (found via a real playtest log): even after LodCacheLoader stopped holding its
    // MappedByteBuffers alive for the whole pipeline session (see that class's FIX note -
    // the original, narrower cause of this same symptom), a *second* player report showed
    // the exact same AccessDeniedException on this rename, this time for nodes.bin at an
    // ordinary world-quit compaction with no `/ev reload` involved at all. That rules out
    // the specific stale-mapping cause a second time - on Windows, a file that was just
    // written to can end up transiently locked by something entirely outside this
    // codebase's control (real-time antivirus scanning a newly-written file being the most
    // common cause; a search indexer or backup tool doing the same is also possible) for a
    // brief window after the write completes, regardless of how careful the JVM-side
    // mmap/GC discipline is. Retrying the rename itself, rather than chasing down every
    // possible external holder one at a time, is the actually-robust fix: it's cheap deep
    // in the failure path (this only ever runs after a real AccessDeniedException, not on
    // the common success path) and works no matter which external process transiently
    // holds the lock.
    private static final int MAX_RENAME_RETRIES = 5;
    private static final long RENAME_RETRY_DELAY_MS = 100;

    private static void moveAtomicallyIfPossible(Path tmp, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    // Some filesystems (notably certain network mounts) don't support atomic
                    // rename across the move; fall back to a plain (non-atomic) replace rather
                    // than failing the whole cache write.
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (java.nio.file.AccessDeniedException e) {
                if (attempt >= MAX_RENAME_RETRIES) {
                    throw e;
                }
                try {
                    Thread.sleep(RENAME_RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Memory-maps {@code file} read-only. Caller must not mutate the returned buffer. */
    static MappedByteBuffer mapReadOnly(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
        }
    }

    /** Opens {@code file} for in-place read/write patching of fixed-size records. */
    static FileChannel openReadWrite(Path file) throws IOException {
        return FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private static IOException unwrapIo(RuntimeException e) {
        return e.getCause() instanceof IOException io ? io : new IOException(e);
    }

    /** Lets {@link Consumer#accept(Object)} lambdas above surface a checked {@link IOException}. */
    static final class UncheckedIoException extends RuntimeException {
        UncheckedIoException(IOException cause) {
            super(cause);
        }
    }
}