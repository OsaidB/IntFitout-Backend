package life.work.IntFit.backend.supplier;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Recency ranking, group boundaries and the frozen browsing order. */
class WorksiteBrowseSnapshotsTest {

    private final WorksiteBrowseSnapshots snaps = new WorksiteBrowseSnapshots();

    /** A Tuesday, so the Saturday-start week began three days earlier. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @Test void businessWeekStartsOnSaturday() {
        assertEquals(LocalDate.of(2026, 10, 24), WorksiteBrowseSnapshots.startOfBusinessWeek(TODAY));
        assertEquals(DayOfWeekSaturday(), WorksiteBrowseSnapshots.startOfBusinessWeek(TODAY).getDayOfWeek());
    }

    private static java.time.DayOfWeek DayOfWeekSaturday() { return java.time.DayOfWeek.SATURDAY; }

    @Test void eachWorksiteAppearsOnceRankedByItsLatestActivity() {
        // 10 was active yesterday AND three months ago: only the recent activity ranks it.
        Map<Long, LocalDate> last = new LinkedHashMap<>();
        last.put(10L, LocalDate.of(2026, 10, 26));
        last.put(20L, LocalDate.of(2026, 10, 10));
        last.put(30L, LocalDate.of(2026, 7, 1));

        var snap = snaps.create(last, TODAY);
        assertEquals(3, snap.size());
        assertEquals(List.of(10L, 20L, 30L), snap.entries().stream().map(e -> e.masterId()).toList());
        assertEquals(1, snap.entries().stream().filter(e -> e.masterId() == 10L).count());
        assertEquals("THIS_WEEK", snap.entries().get(0).group());
    }

    @Test void groupBoundariesDoNotOverlapAndCoverEverything() {
        LocalDate weekStart = WorksiteBrowseSnapshots.startOfBusinessWeek(TODAY);
        // Saturday of this week, and the day before it.
        assertEquals("THIS_WEEK", WorksiteBrowseSnapshots.groupFor(weekStart, TODAY, weekStart));
        assertEquals("LAST_30_DAYS", WorksiteBrowseSnapshots.groupFor(weekStart.minusDays(1), TODAY, weekStart));
        // 29 days ago is still the first window; 30 days ago starts the next.
        assertEquals("LAST_30_DAYS", WorksiteBrowseSnapshots.groupFor(TODAY.minusDays(29), TODAY, weekStart));
        assertEquals("DAYS_30_59", WorksiteBrowseSnapshots.groupFor(TODAY.minusDays(30), TODAY, weekStart));
        assertEquals("DAYS_30_59", WorksiteBrowseSnapshots.groupFor(TODAY.minusDays(59), TODAY, weekStart));
        assertEquals("DAYS_60_89", WorksiteBrowseSnapshots.groupFor(TODAY.minusDays(60), TODAY, weekStart));
        assertEquals("NEVER_ACTIVE", WorksiteBrowseSnapshots.groupFor(null, TODAY, weekStart));
    }

    @Test void orderIsDeterministicWhenDatesTie() {
        Map<Long, LocalDate> last = new LinkedHashMap<>();
        last.put(50L, LocalDate.of(2026, 10, 20));
        last.put(5L, LocalDate.of(2026, 10, 20));
        last.put(30L, LocalDate.of(2026, 10, 20));
        var a = snaps.create(last, TODAY);
        var b = snaps.create(new LinkedHashMap<>(last), TODAY);
        assertEquals(a.entries().stream().map(e -> e.masterId()).toList(),
                b.entries().stream().map(e -> e.masterId()).toList());
        assertEquals(List.of(5L, 30L, 50L), a.entries().stream().map(e -> e.masterId()).toList());
    }

    @Test void pagingASnapshotNeverSkipsOrRepeatsWhenActivityChanges() {
        Map<Long, LocalDate> last = new LinkedHashMap<>();
        for (long i = 1; i <= 10; i++) last.put(i, LocalDate.of(2026, 10, 10).minusDays(i));
        var snap = snaps.create(last, TODAY);

        List<Long> page1 = snap.slice(0, 4).stream().map(e -> e.masterId()).toList();
        // Worksite 9 becomes the most recent after page 1 was served; the snapshot is frozen.
        last.put(9L, TODAY);
        List<Long> page2 = snap.slice(4, 4).stream().map(e -> e.masterId()).toList();
        List<Long> page3 = snap.slice(8, 4).stream().map(e -> e.masterId()).toList();

        List<Long> seen = new ArrayList<>();
        seen.addAll(page1); seen.addAll(page2); seen.addAll(page3);
        assertEquals(10, seen.size(), "every worksite appears exactly once across pages");
        assertEquals(10, new HashSet<>(seen).size(), "no duplicates");
        assertEquals(snap.entries().stream().map(e -> e.masterId()).toList(), seen);
    }

    @Test void refreshingRebuildsTheOrder() {
        Map<Long, LocalDate> last = new LinkedHashMap<>();
        last.put(1L, LocalDate.of(2026, 10, 1));
        last.put(2L, LocalDate.of(2026, 10, 2));
        var first = snaps.create(last, TODAY);
        assertEquals(2L, first.entries().get(0).masterId());

        last.put(1L, TODAY); // new activity
        var second = snaps.create(last, TODAY);
        assertEquals(1L, second.entries().get(0).masterId());
        assertNotEquals(first.id(), second.id());
        // The old session still exists and still has its own order.
        assertTrue(snaps.get(first.id()).isPresent());
        assertEquals(2L, snaps.get(first.id()).orElseThrow().entries().get(0).masterId());
    }

    @Test void unknownSnapshotIsReportedRatherThanSilentlyRebuilt() {
        assertTrue(snaps.get("does-not-exist").isEmpty());
    }
}
