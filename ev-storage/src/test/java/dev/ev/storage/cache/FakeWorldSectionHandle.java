package dev.ev.storage.cache;

import dev.ev.api.SectionPos;
import dev.ev.api.storage.WorldSectionHandle;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal in-memory {@link WorldSectionHandle} fake for tests — no real voxel storage. */
final class FakeWorldSectionHandle implements WorldSectionHandle {

    private final SectionPos position;
    private final AtomicInteger refCount = new AtomicInteger(0);

    FakeWorldSectionHandle(SectionPos position) {
        this.position = position;
    }

    @Override
    public SectionPos position() {
        return position;
    }

    @Override
    public int getVoxel(int localX, int localY, int localZ) {
        return 0;
    }

    @Override
    public void setVoxel(int localX, int localY, int localZ, int paletteIndex) {
        // no-op fake
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public WorldSectionHandle retain() {
        refCount.incrementAndGet();
        return this;
    }

    @Override
    public void release() {
        refCount.decrementAndGet();
    }

    @Override
    public int refCount() {
        return refCount.get();
    }
}
