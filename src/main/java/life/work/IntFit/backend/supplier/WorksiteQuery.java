package life.work.IntFit.backend.supplier;

import life.work.IntFit.backend.config.TimeConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Locale;

/**
 * One normalized definition of "which projects am I looking at".
 *
 * The list and its summary must always describe the same set, so both are built from this
 * single object rather than from two sets of request parameters that could drift apart.
 *
 * A browsing snapshot is bound to the fingerprint below: paging is only allowed to continue
 * while the criteria are unchanged, which stops a snapshot built for one question being
 * paged under another.
 */
public record WorksiteQuery(
        LocalDate from,            // null = all time
        LocalDate to,              // null = all time
        String q,                  // free text over name / nickname / notes
        Status status,
        Integer lastActiveWithinDays,   // separate from the financial period
        Sort sort,
        boolean ascending
) {
    public enum Status { ALL, STILL_TO_PAY, NO_PAYMENTS, PARTLY_PAID, PAID_IN_FULL, CREDIT }

    /**
     * REMAINING, CREDIT and NAME are all-time measures. PERIOD_SUPPLIES and PERIOD_PAID fall
     * back to the all-time amounts when no period is selected, so an amount sort never orders
     * by a column that is null for every row.
     */
    public enum Sort { RECENT_ACTIVITY, REMAINING, CREDIT, PERIOD_SUPPLIES, PERIOD_PAID, NAME }

    public boolean hasPeriod() { return from != null && to != null; }

    /** Group headers only make sense while the list is in date order. */
    public boolean groupedByRecency() { return sort == Sort.RECENT_ACTIVITY; }

    /**
     * The defaults an existing client gets by sending nothing at all: the whole history,
     * every project, newest activity first. That keeps the original recency browsing intact.
     */
    public static WorksiteQuery defaults() {
        return new WorksiteQuery(null, null, null, Status.ALL, null, Sort.RECENT_ACTIVITY, false);
    }

    public static WorksiteQuery of(LocalDate from, LocalDate to, String q, String status,
                                   Integer lastActiveWithinDays, String sort, String dir) {
        LocalDate f = from, t = to;
        // A half-open period needs both ends; one alone is ambiguous, so it is ignored.
        if (f == null || t == null) { f = null; t = null; }
        if (f != null && t.isBefore(f)) { LocalDate swap = f; f = t; t = swap; }

        String text = (q == null || q.isBlank()) ? null : q.trim();
        Status st = parse(Status.class, status, Status.ALL);
        Sort so = parse(Sort.class, sort, Sort.RECENT_ACTIVITY);
        boolean asc = "asc".equalsIgnoreCase(dir);
        Integer within = (lastActiveWithinDays != null && lastActiveWithinDays > 0)
                ? lastActiveWithinDays : null;
        return new WorksiteQuery(f, t, text, st, within, so, asc);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Saturday-start week, inclusive of Friday, in the business zone. */
    public static LocalDate startOfWeek(LocalDate anchor) {
        return anchor.minusDays((anchor.getDayOfWeek().getValue() + 1) % 7);
    }

    public static LocalDate today() { return LocalDate.now(TimeConfig.BUSINESS_ZONE); }

    /**
     * Identity of the criteria. Any change produces a different value, so a snapshot taken
     * under one question can never be paged under another.
     */
    public String fingerprint() {
        String canonical = String.join("|",
                String.valueOf(from), String.valueOf(to),
                q == null ? "" : q.toLowerCase(Locale.ROOT),
                status.name(), String.valueOf(lastActiveWithinDays),
                sort.name(), ascending ? "asc" : "desc");
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
