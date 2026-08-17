package dev.ev.render.framegraph;

/**
 * Fluent builder handle returned by {@link FrameGraphBuilder#addPass}, used to declare
 * resource dependencies (reads/writes) for a pass.
 */
public interface PassBuilder {

    /**
     * Declares that the pass reads the given resources.
     *
     * @param resources resources read by the pass
     * @return this PassBuilder for method chaining
     */
    PassBuilder reads(FrameResource... resources);

    /**
     * Declares that the pass writes to the given resources.
     *
     * @param resources resources written by the pass
     * @return this PassBuilder for method chaining
     */
    PassBuilder writes(FrameResource... resources);

    /**
     * Returns a synthetic or primary output {@link FrameResource} representing this pass's output,
     * allowing convenient chaining into subsequent passes' {@code reads()} calls.
     *
     * @return primary output resource of this pass
     */
    FrameResource output();
}
