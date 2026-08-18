package dev.ev.gpu.gl;

import dev.ev.api.gpu.FenceHandle;

/**
 * {@link FenceHandle} implementation wrapping a raw GL sync object handle, as returned by
 * {@code glFenceSync}. Created exclusively by {@link GLRenderBackend#insertFence()}; {@link
 * GLRenderBackend#isSignaled} and {@link GLRenderBackend#waitForFence} both require the
 * {@link FenceHandle} passed back in to be an instance of this class, unwrapping it via
 * {@link #syncHandle()} to obtain the native handle for the corresponding {@code
 * glClientWaitSync}/{@code glDeleteSync} calls.
 *
 * @param syncHandle raw GL sync object handle, as returned by {@code glFenceSync}
 */
public record GLFenceHandle(long syncHandle) implements FenceHandle {}
