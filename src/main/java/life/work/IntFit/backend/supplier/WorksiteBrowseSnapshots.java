package life.work.IntFit.backend.supplier;

import life.work.IntFit.backend.config.TimeConfig;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A frozen browsing order for the worksite list.
 *
 * Keyset paging alone is not enough here: the sort key is "latest qualifying activity",
 * which changes whenever an assignment or invoice is recorded. A worksite that gains
 * activity between two page requests would jump to the front and could be shown twice, or
 * push another off the end unseen.
 *
 * So the whole ordered list of master ids is computed once and kept for the session. Paging
 * walks that fixed list by offset, which cannot skip or repeat. Balances are read live on
 * every page, so only the order is frozen, never the money. Asking without a snapshot id
 * rebuilds it, which is what pull-to-refresh does.
 *
 * At this project's scale (about 116 masters) a snapshot is a few kilobytes, so an in-memory
 * map with a short expiry is the right size of solution.
 */
@Component
public class WorksiteBrowseSnapshots {

    /** How long an idle browsing session keeps its order. */
    static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_SNAPSHOTS = 200;

    public record Entry(long masterId, LocalDate lastActivityOn, String group) { }

    public record Snapshot(String id, String criteria, List<Entry> entries, Instant createdAt) {
        public int size() { return entries.size(); }
        public List<Entry> slice(int from, int limit) {
            if (from >= entries.size()) return List.of();
            return entries.subList(from, Math.min(entries.size(), from + limit));
        }
    }

    private final Map<String, Snapshot> store = new ConcurrentHashMap<>();

    /**
     * Rank every master worksite once, newest activity first.
     *
     * Groups are non-overlapping and cover the whole timeline: the current business week
     * (Saturday start, matching payroll), then the rest of the last 30 days, then
     * successive 30-day windows, then worksites with no qualifying activity at all. A
     * worksite active yesterday and also three months ago sits only in the recent group,
     * because only its latest activity is ranked.
     */
    public Snapshot create(Map<Long, LocalDate> lastActivity, LocalDate today) {
        return create(lastActivity, today, "");
    }

    /**
     * A snapshot remembers the criteria that produced it. Paging it under different criteria
     * is refused rather than silently answered from the wrong ordering.
     */
    public Snapshot create(Map<Long, LocalDate> lastActivity, LocalDate today, String criteria) {
        LocalDate weekStart = startOfBusinessWeek(today);

        List<Entry> entries = lastActivity.entrySet().stream()
                .map(e -> new Entry(e.getKey(), e.getValue(), groupFor(e.getValue(), today, weekStart)))
                // Deterministic: newest first, then by id so equal dates never reorder.
                .sorted(Comparator.comparing(Entry::lastActivityOn, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Entry::masterId))
                .toList();

        evictIfNeeded();
        String id = UUID.randomUUID().toString();
        Snapshot snap = new Snapshot(id, criteria, entries, Instant.now());
        store.put(id, snap);
        return snap;
    }

    /** Freeze an order the caller has already decided, e.g. by amount rather than by date. */
    public Snapshot createOrdered(List<Entry> ordered, String criteria) {
        evictIfNeeded();
        String id = UUID.randomUUID().toString();
        Snapshot snap = new Snapshot(id, criteria, List.copyOf(ordered), Instant.now());
        store.put(id, snap);
        return snap;
    }

    public Optional<Snapshot> get(String id) {
        Snapshot s = store.get(id);
        if (s == null) return Optional.empty();
        if (Instant.now().isAfter(s.createdAt().plus(TTL))) {
            store.remove(id);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    /** Saturday-start week, the same convention payroll already uses. */
    public static LocalDate startOfBusinessWeek(LocalDate anchor) {
        DayOfWeek dow = anchor.getDayOfWeek();
        return anchor.minusDays((dow.getValue() + 1) % 7);
    }

    /**
     * Boundaries are inclusive at the recent end and exclusive at the old end, so no date
     * can fall in two groups and none can fall in none.
     */
    static String groupFor(LocalDate last, LocalDate today, LocalDate weekStart) {
        if (last == null) return "NEVER_ACTIVE";
        if (!last.isBefore(weekStart)) return "THIS_WEEK";

        long days = java.time.temporal.ChronoUnit.DAYS.between(last, today);
        if (days < 30) return "LAST_30_DAYS";
        long window = days / 30;              // 1 => 30..59, 2 => 60..89, ...
        long from = window * 30;
        long to = from + 29;
        return "DAYS_" + from + "_" + to;
    }

    private void evictIfNeeded() {
        if (store.size() < MAX_SNAPSHOTS) return;
        Instant cutoff = Instant.now().minus(TTL);
        store.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
        if (store.size() >= MAX_SNAPSHOTS) {
            store.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getValue().createdAt()))
                    .limit(Math.max(1, store.size() - MAX_SNAPSHOTS + 1))
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(store::remove);
        }
    }

    /** Test seam: the business zone used when the caller does not pass a date. */
    public LocalDate today() { return LocalDate.now(TimeConfig.BUSINESS_ZONE); }
}
