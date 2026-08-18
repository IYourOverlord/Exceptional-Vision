package dev.ev.api.gpu;

/**
 * Optional capability interface for {@link RenderBackend} implementations that can
 * construct a fresh {@link CommandList} instance for callers to record into before
 * calling {@link RenderBackend#submit}.
 *
 * <h2>Why this exists (ticket 31 integration note)</h2>
 * {@link RenderBackend} (ticket 04) intentionally has no method to obtain a
 * {@code CommandList} — {@link RenderBackend#submit} only ever accepts one. Every
 * concrete backend built against this contract so far has needed a way to hand callers
 * a fresh, empty {@code CommandList} anyway: {@code FakeRenderBackend.newCommandList()}
 * (ticket 30, an in-memory test backend) and {@code GLRenderBackend.newCommandList()}
 * (ticket 31, the real OpenGL backend) both added the same method independently, purely
 * as call-site conveniences on their own concrete types. That worked for callers that
 * already know the concrete backend type, but {@code dev.ev.render.framegraph.FrameGraphBuilder}
 * (ticket 24) only holds a plain {@link RenderBackend} reference — it has no way to call
 * a backend-specific {@code newCommandList()} method without either an unsafe cast to a
 * concrete class (defeating the point of the {@link RenderBackend} abstraction) or
 * pulling a concrete backend module as a compile dependency of {@code ev-render} (which
 * would invert the intended dependency direction: {@code ev-render} depends on the
 * {@code RenderBackend} abstraction, not on any specific backend implementation).
 *
 * <p>This interface closes that gap: a {@link RenderBackend} implementation that can
 * vend command lists additionally implements this interface, and callers holding only a
 * {@link RenderBackend} reference can check {@code backend instanceof CommandListFactory}
 * before calling {@link #newCommandList()} — no cast to a concrete class, no new compile
 * dependency. Implementing this interface is optional: a {@link RenderBackend} that has
 * no meaningful way to vend a standalone {@code CommandList} (for example, a backend
 * whose {@link RenderBackend#submit} only ever accepts command lists it handed out
 * through some other backend-specific mechanism not modeled here) simply does not
 * implement it, and callers fall back to whatever mechanism that backend does expose.
 */
public interface CommandListFactory {

    /**
     * Returns a fresh, empty {@link CommandList} ready to be recorded into and later
     * passed to {@link RenderBackend#submit}. Each call returns a new, independent
     * instance — the returned {@code CommandList} is not shared or reused internally by
     * the backend.
     */
    CommandList newCommandList();
}
