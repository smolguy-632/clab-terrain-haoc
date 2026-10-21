package me.cipher.clabhaoc.haoc;

/**
 * Per-section hardware occlusion culling state. Instances are owned exclusively by
 * {@link TerrainHaocManager} and referenced from an {@code IdentityHashMap} keyed by
 * {@code RenderSection}.
 */
final class HaocState {
    static final int STATUS_UNKNOWN = 0;
    static final int STATUS_VISIBLE = 1;
    static final int STATUS_OCCLUDED = 2;

    static final int QUERY_NONE = -1;

    /** The last known visibility of this section, or UNKNOWN. */
    int status = STATUS_UNKNOWN;

    /** The occlusion query currently running for this section, or QUERY_NONE. */
    int queryId = QUERY_NONE;

    /** The frame in which the running (or most recent) query was submitted. */
    int submittedFrame = -1;

    /** The frame in which the most recent completed test returned its result. */
    int testFrame = -1;

    boolean isTestPending() {
        return this.queryId != QUERY_NONE;
    }

    boolean isSuppressed() {
        return this.queryId != QUERY_NONE || this.status == STATUS_OCCLUDED;
    }

    void reset() {
        this.status = STATUS_UNKNOWN;
        this.queryId = QUERY_NONE;
        this.submittedFrame = -1;
        this.testFrame = -1;
    }
}
