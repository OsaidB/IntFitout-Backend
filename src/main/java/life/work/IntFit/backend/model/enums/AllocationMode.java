package life.work.IntFit.backend.model.enums;

/** How one hour category (regular or overtime) of a member's day is spread across worksites. */
public enum AllocationMode {
    /** Equal split across the member's worksites for that day. */
    AUTO,
    /** Explicit per-worksite values, including an explicitly chosen equal split. */
    CUSTOM;

    public static AllocationMode of(Object override) {
        return override == null ? AUTO : CUSTOM;
    }
}
