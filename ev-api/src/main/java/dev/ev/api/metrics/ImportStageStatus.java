package dev.ev.api.metrics;

/**
 * Snapshot of how many sections/tasks are in each stage of the cold-start bulk
 * import/build pipeline (queued-for-read, retrying-after-failure, actively
 * building, done). Distinct from the generic queue-depth/counter metrics above
 * because during cold start, a single "queue depth" number does not tell the
 * user WHERE the backlog actually is — this staged breakdown is what a user or
 * developer actually needs to diagnose whether progress is being made or the
 * pipeline is stuck (e.g. large "retrying" count indicates a systemic problem
 * like disk contention or a bug, not just "still working").
 *
 * Modeled after an explicit staged status breakdown found valuable in an
 * independent implementation of this same mod concept (Exceptional Vision,
 * `/ev status` command) — a plain aggregate queue-depth number was found in
 * practice to be less actionable for diagnosing stuck/slow bulk imports than
 * an explicit per-stage breakdown.
 */
public record ImportStageStatus(
    int queuedForRead,
    int retryingAfterFailure,
    int activelyBuilding,
    int completed,
    int totalKnown
) {
    public static ImportStageStatus empty() {
        return new ImportStageStatus(0, 0, 0, 0, 0);
    }

    /** Fraction complete in [0,1], or 0.0 if totalKnown is 0 (nothing to report yet, not division-by-zero garbage). */
    public double completionFraction() {
        return totalKnown == 0 ? 0.0 : (double) completed / totalKnown;
    }
}
