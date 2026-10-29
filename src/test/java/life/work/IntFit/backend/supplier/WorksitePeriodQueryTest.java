package life.work.IntFit.backend.supplier;

import life.work.IntFit.backend.supplier.SupplierDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Financial periods, search, status filters and sorting, against the isolated test database.
 *
 * The distinction under test throughout: a FINANCIAL PERIOD selects dated supplies and
 * payments, while LATEST ACTIVITY only orders projects. One is never used as a stand-in for
 * the other.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorksitePeriodQueryTest {

    @Autowired SupplierPaymentService service;
    @Autowired JdbcTemplate jdbc;

    // Saturday 24 Oct 2026 starts the week that contains Tuesday 27 Oct.
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final LocalDate THIS_WEEK_START = LocalDate.of(2026, 10, 24);
    private static final LocalDate LAST_WEEK_START = LocalDate.of(2026, 10, 17);
    private static final LocalDate LAST_WEEK_END = LocalDate.of(2026, 10, 23);

    private static final long OLD_PROJECT = 701L;   // supplies last week, no recent activity
    private static final long FRESH = 702L;         // supplies this week
    private static final long PAID_UP = 703L;       // supplies this week, already paid in full
    private static final long ZERO_NET = 704L;      // a supply and an equal return this week
    private static final long QUIET = 705L;         // all-time supplies, nothing in any period

    private static BigDecimal n(String v) { return new BigDecimal(v); }
    private static String key() { return UUID.randomUUID().toString(); }

    @BeforeEach
    void seed() {
        for (String t : List.of("supplier_payment_idempotency", "supplier_allocation_change",
                "supplier_payment_allocation", "supplier_payment", "cheque_movement",
                "ar_payments", "invoice", "work_assignments", "worksites", "master_worksites")) {
            jdbc.update("DELETE FROM " + t);
        }

        master(OLD_PROJECT, "مشروع رامي الرامي", "رامي", "الرامي تكملة الطلبية, رام الرامي");
        master(FRESH, "Fresh Site", null, null);
        master(PAID_UP, "Paid Up Site", null, null);
        master(ZERO_NET, "Zero Net Site", null, null);
        master(QUIET, "Quiet Site", null, null);

        // Supplies last week only; its latest activity is therefore outside this week.
        invoice(1, OLD_PROJECT, "5000", "2026-10-20 10:00:00");
        // Supplies this week.
        invoice(2, FRESH, "8000", "2026-10-26 09:00:00");
        invoice(3, PAID_UP, "2000", "2026-10-25 09:00:00");
        // A delivery and an applied return that cancel out, this week.
        invoice(4, ZERO_NET, "1500", "2026-10-24 09:00:00");
        invoice(5, ZERO_NET, "-1500", "2026-10-26 09:00:00");
        // Older supplies, nothing in either recent week.
        invoice(6, QUIET, "3000", "2026-05-01 09:00:00");
    }

    private void master(long id, String name, String nickname, String notes) {
        jdbc.update("INSERT INTO master_worksites(id, approved_name, nickname, notes) VALUES (?,?,?,?)",
                id, name, nickname, notes);
        jdbc.update("INSERT INTO worksites(id, name, is_archived, master_worksite_id) VALUES (?,?,FALSE,?)",
                id * 10, name + " sub", id);
    }

    private void invoice(long id, long master, String total, String ts) {
        jdbc.update("INSERT INTO invoice(id, worksite_id, total, net_total, date) VALUES (?,?,?,?,?)",
                id, master * 10, new BigDecimal(total), new BigDecimal(total),
                java.sql.Timestamp.valueOf(ts));
    }

    private long pay(String amount, String date, Object... allocPairs) {
        var lines = new java.util.ArrayList<AllocationInput>();
        for (int i = 0; i < allocPairs.length; i += 2) {
            lines.add(new AllocationInput((Long) allocPairs[i], n((String) allocPairs[i + 1])));
        }
        return service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CASH, n(amount),
                LocalDate.parse(date), null, null, null, null, "t", lines)).id();
    }

    private WorksitePage page(WorksiteQuery q) { return service.browse(null, 0, 100, TODAY, q); }

    private static WorksiteQuery thisWeek() {
        return WorksiteQuery.of(THIS_WEEK_START, TODAY, null, null, null, null, null);
    }

    private static List<Long> ids(WorksitePage p) {
        return p.rows().stream().map(WorksiteRow::masterWorksiteId).toList();
    }

    private static WorksiteRow pick(WorksitePage p, long id) {
        return p.rows().stream().filter(r -> r.masterWorksiteId() == id).findFirst().orElseThrow();
    }

    // ================================================================ periods

    @Test void defaultsKeepTheOriginalRecencyBrowsing() {
        var p = page(WorksiteQuery.defaults());
        assertTrue(p.grouped(), "the default list is in date order, so headers apply");
        assertNull(p.summary().periodSelected() ? Boolean.TRUE : null);
        assertFalse(p.summary().periodSelected());
        // Every project is listed, none excluded by a period.
        assertTrue(ids(p).containsAll(List.of(OLD_PROJECT, FRESH, PAID_UP, ZERO_NET, QUIET)));
        assertNull(p.rows().get(0).periodSupplies(), "no period means no period column");
    }

    @Test void aPeriodSelectsDatedRecordsNotLatestActivity() {
        var p = page(thisWeek());
        // OLD_PROJECT was supplied last week, so it is out even though it exists.
        assertFalse(ids(p).contains(OLD_PROJECT));
        assertTrue(ids(p).contains(FRESH));
        // QUIET has all-time supplies but none this week.
        assertFalse(ids(p).contains(QUIET));
    }

    @Test void lastWeekFindsAProjectWhoseLatestActivityIsOutsideIt() {
        var lastWeek = WorksiteQuery.of(LAST_WEEK_START, LAST_WEEK_END, null, null, null, null, null);
        var p = page(lastWeek);
        assertTrue(ids(p).contains(OLD_PROJECT));
        assertEquals(0, pick(p, OLD_PROJECT).periodSupplies().compareTo(n("5000.00")));
        assertFalse(ids(p).contains(FRESH), "this week's supplies are not last week's");
    }

    @Test void aFullyPaidProjectStillAppearsInItsWeek() {
        pay("2000", "2026-10-26", PAID_UP, "2000");
        var p = page(thisWeek());
        assertTrue(ids(p).contains(PAID_UP), "paid in full is still activity");
        var row = pick(p, PAID_UP);
        assertEquals(0, row.remainingToCover().compareTo(n("0.00")));
        assertEquals(0, row.periodSupplies().compareTo(n("2000.00")));
        assertEquals(0, row.periodAllocated().compareTo(n("2000.00")));
    }

    @Test void supplyAndReturnThatCancelOutStillCountAsActivity() {
        var p = page(thisWeek());
        assertTrue(ids(p).contains(ZERO_NET), "a zero net total is not the absence of records");
        assertEquals(0, pick(p, ZERO_NET).periodSupplies().compareTo(n("0.00")));
    }

    @Test void aNegativePeriodTotalIsReportedHonestly() {
        // Only the return falls in this narrow window.
        var q = WorksiteQuery.of(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 26),
                null, null, null, null, null);
        var p = page(q);
        assertEquals(0, pick(p, ZERO_NET).periodSupplies().compareTo(n("-1500.00")),
                "returns exceeding supply show as negative rather than floored");
    }

    @Test void midnightBoundariesAreHalfOpenSoADayBelongsToOnePeriod() {
        invoice(7, FRESH, "100", "2026-10-24 00:00:00");   // first instant of this week
        invoice(8, FRESH, "200", "2026-10-23 23:59:59");   // last instant of last week

        var thisW = page(thisWeek());
        assertEquals(0, pick(thisW, FRESH).periodSupplies().compareTo(n("8100.00")));

        var lastW = page(WorksiteQuery.of(LAST_WEEK_START, LAST_WEEK_END, null, null, null, null, null));
        assertEquals(0, pick(lastW, FRESH).periodSupplies().compareTo(n("200.00")));
    }

    @Test void aLateImportLandsInTheSupplyWeekNotTheImportWeek() {
        // Supplied last week, imported days later: the supply date decides.
        invoice(9, FRESH, "700", "2026-10-21 09:00:00");
        var lastW = page(WorksiteQuery.of(LAST_WEEK_START, LAST_WEEK_END, null, null, null, null, null));
        assertEquals(0, pick(lastW, FRESH).periodSupplies().compareTo(n("700.00")));
        assertEquals(0, pick(page(thisWeek()), FRESH).periodSupplies().compareTo(n("8000.00")));
    }

    @Test void paymentsUseThePaymentDateAndIgnoreEntryTime() {
        pay("1000", "2026-10-20", FRESH, "1000");   // dated last week, entered now
        assertEquals(0, pick(page(thisWeek()), FRESH).periodAllocated().compareTo(n("0.00")));
        var lastW = page(WorksiteQuery.of(LAST_WEEK_START, LAST_WEEK_END, null, null, null, null, null));
        assertEquals(0, pick(lastW, FRESH).periodAllocated().compareTo(n("1000.00")));
    }

    // ================================================================ split payments

    @Test void aSplitPaymentIsCountedOncePaidButSharedPerProject() {
        pay("10000", "2026-10-26", FRESH, "6000", PAID_UP, "3000");   // 1,000 unassigned

        var p = page(thisWeek());
        assertEquals(0, pick(p, FRESH).periodAllocated().compareTo(n("6000.00")));
        assertEquals(0, pick(p, PAID_UP).periodAllocated().compareTo(n("3000.00")));

        Summary sum = p.summary();
        assertEquals(0, sum.actualPaidAllProjects().compareTo(n("10000.00")),
                "the payment is counted once, in full");
        assertEquals(0, sum.matchingPeriodAllocated().compareTo(n("9000.00")),
                "project shares total 9,000, not 10,000");
        assertEquals(0, sum.unallocatedPaidInPeriod().compareTo(n("1000.00")));
    }

    @Test void searchingOneProjectShowsItsShareNotTheWholePayment() {
        pay("10000", "2026-10-26", FRESH, "6000", PAID_UP, "3000");

        var q = WorksiteQuery.of(THIS_WEEK_START, TODAY, "Fresh", null, null, null, null);
        var p = page(q);
        assertEquals(List.of(FRESH), ids(p));
        assertEquals(0, pick(p, FRESH).periodAllocated().compareTo(n("6000.00")));

        Summary sum = p.summary();
        assertEquals(0, sum.matchingPeriodAllocated().compareTo(n("6000.00")),
                "only the matching project's share");
        assertEquals(0, sum.actualPaidAllProjects().compareTo(n("10000.00")),
                "the supplier-wide total is unchanged by the search");
    }

    // ================================================================ search

    @Test void searchMatchesApprovedNameNicknameAndNotes() {
        assertEquals(List.of(OLD_PROJECT), ids(page(
                WorksiteQuery.of(null, null, "رامي", null, null, null, null))), "nickname");
        assertEquals(List.of(OLD_PROJECT), ids(page(
                WorksiteQuery.of(null, null, "رام الرامي", null, null, null, null))), "alt name in notes");
        assertEquals(List.of(FRESH), ids(page(
                WorksiteQuery.of(null, null, "fresh", null, null, null, null))), "case-insensitive English");
    }

    @Test void searchFindsAnOldProjectRegardlessOfRecency() {
        var p = page(WorksiteQuery.of(null, null, "الرامي", null, null, null, null));
        assertEquals(List.of(OLD_PROJECT), ids(p), "an old project is still searchable");
    }

    // ================================================================ status

    @Test void statusFiltersUseAllTimeAmountsAndNarrowThePeriodSet() {
        pay("2000", "2026-10-26", PAID_UP, "2000");

        var paidInFull = WorksiteQuery.of(THIS_WEEK_START, TODAY, null, "PAID_IN_FULL", null, null, null);
        assertEquals(List.of(PAID_UP), ids(page(paidInFull)));

        var noPayments = WorksiteQuery.of(THIS_WEEK_START, TODAY, null, "NO_PAYMENTS", null, null, null);
        assertTrue(ids(page(noPayments)).contains(FRESH));
        assertFalse(ids(page(noPayments)).contains(PAID_UP));
    }

    @Test void creditStatusAndSortAreAvailable() {
        pay("5000", "2026-10-26", PAID_UP, "5000");   // supplies 2,000 -> credit 3,000
        var credit = WorksiteQuery.of(null, null, null, "CREDIT", null, "CREDIT", "desc");
        var p = page(credit);
        assertEquals(List.of(PAID_UP), ids(p));
        assertEquals(0, pick(p, PAID_UP).credit().compareTo(n("3000.00")));
    }

    // ================================================================ sorting

    @Test void amountSortsRunAscendingAndDescendingAcrossTheWholeSet() {
        var desc = WorksiteQuery.of(null, null, null, null, null, "REMAINING", "desc");
        var asc = WorksiteQuery.of(null, null, null, null, null, "REMAINING", "asc");
        List<Long> d = ids(page(desc)), a = ids(page(asc));
        assertEquals(d, a.reversed(), "ascending is the exact reverse");
        assertEquals(FRESH, d.get(0), "8,000 outstanding leads");
    }

    @Test void forAllTimeAnAmountSortUsesAllTimeFiguresNotNullPeriodFields() {
        // No period selected, so PERIOD_SUPPLIES must fall back to all-time supplies.
        var p = page(WorksiteQuery.of(null, null, null, null, null, "PERIOD_SUPPLIES", "desc"));
        assertEquals(FRESH, ids(p).get(0), "8,000 all-time supplies leads");
        assertNull(p.rows().get(0).periodSupplies());
    }

    @Test void groupHeadersAppearOnlyUnderDateOrdering() {
        assertTrue(page(WorksiteQuery.defaults()).grouped());
        assertFalse(page(WorksiteQuery.of(null, null, null, null, null, "REMAINING", "desc")).grouped());
        assertNull(page(WorksiteQuery.of(null, null, null, null, null, "NAME", "asc"))
                .rows().get(0).group(), "no group label when not in date order");
    }

    @Test void sortingIsStableAcrossPages() {
        var q = WorksiteQuery.of(null, null, null, null, null, "REMAINING", "desc");
        var first = service.browse(null, 0, 2, TODAY, q);
        var second = service.browse(first.snapshotId(), first.nextOffset(), 2, TODAY, q);
        var third = service.browse(second.snapshotId(), second.nextOffset(), 5, TODAY, q);

        var seen = new java.util.ArrayList<Long>();
        seen.addAll(ids(first)); seen.addAll(ids(second)); seen.addAll(ids(third));
        assertEquals(ids(page(q)), seen, "paging reproduces the single-page order exactly");
        assertEquals(seen.size(), new java.util.HashSet<>(seen).size(), "no repeats");
    }

    // ================================================================ snapshots and summary

    @Test void pagingASnapshotUnderDifferentCriteriaIsRefused() {
        var q = WorksiteQuery.of(null, null, null, null, null, "REMAINING", "desc");
        var first = service.browse(null, 0, 2, TODAY, q);
        var other = WorksiteQuery.of(null, null, null, null, null, "NAME", "asc");
        var ex = assertThrows(SupplierPaymentService.Problem.class,
                () -> service.browse(first.snapshotId(), first.nextOffset(), 2, TODAY, other));
        assertEquals(409, ex.status);
    }

    @Test void theSummaryDescribesExactlyTheMatchingSet() {
        pay("2000", "2026-10-26", PAID_UP, "2000");
        var q = WorksiteQuery.of(THIS_WEEK_START, TODAY, null, null, null, null, null);
        var p = page(q);

        Summary sum = p.summary();
        assertEquals(p.total(), sum.matchingProjects());
        assertEquals(sum.matchingProjects(), ids(p).size());
        // Summary endpoint and page summary agree.
        Summary direct = service.summary(q, TODAY);
        assertEquals(sum.matchingProjects(), direct.matchingProjects());
        assertEquals(0, sum.matchingPeriodSupplies().compareTo(direct.matchingPeriodSupplies()));
        assertTrue(sum.periodSelected());
        assertEquals(THIS_WEEK_START, sum.from());
    }

    @Test void stillToPayStaysCumulativeWhateverThePeriod() {
        pay("1000", "2026-05-02", QUIET, "1000");   // an old payment, outside both weeks
        var thisW = page(thisWeek()).summary();
        var allTime = page(WorksiteQuery.defaults()).summary();

        // The period view excludes QUIET, so the two matching sets differ - but neither
        // figure mixes weekly supplies with all-time payments.
        assertTrue(thisW.periodSelected());
        assertNull(allTime.matchingPeriodSupplies(), "all time has no period column");
        assertTrue(allTime.stillToPayOverall().signum() > 0);
        var quietRow = page(WorksiteQuery.of(null, null, "Quiet", null, null, null, null)).rows().get(0);
        assertEquals(0, quietRow.supplies().compareTo(n("3000.00")));
        assertEquals(0, quietRow.paid().compareTo(n("1000.00")));
        assertEquals(0, quietRow.remainingToCover().compareTo(n("2000.00")), "cumulative, not weekly");
    }

    @Test void periodLabelledUnassignedAndUnallocatedUseThatPeriod() {
        // A worksite with no master: its supplies reach no project.
        jdbc.update("INSERT INTO worksites(id, name, is_archived, master_worksite_id) "
                + "VALUES (999,'orphan',FALSE,NULL)");
        jdbc.update("INSERT INTO invoice(id, worksite_id, total, net_total, date) VALUES (20,999,?,?,?)",
                new BigDecimal("400"), new BigDecimal("400"),
                java.sql.Timestamp.valueOf("2026-10-26 09:00:00"));
        jdbc.update("INSERT INTO invoice(id, worksite_id, total, net_total, date) VALUES (21,999,?,?,?)",
                new BigDecimal("900"), new BigDecimal("900"),
                java.sql.Timestamp.valueOf("2026-05-02 09:00:00"));

        var sum = page(thisWeek()).summary();
        assertEquals(0, sum.unassignedSuppliesInPeriod().compareTo(n("400.00")),
                "only this week's unassigned supplies");
    }

    @Test void lastActiveRestrictionIsSeparateFromTheFinancialPeriod() {
        jdbc.update("DELETE FROM team_members WHERE id = 9001");
        jdbc.update("INSERT INTO team_members(id, name, role) VALUES (9001,'Fixture worker','WORKER')");
        jdbc.update("INSERT INTO work_assignments(id, team_member_id, master_worksite_id, date) "
                + "VALUES (1,9001,?,?)", FRESH, LocalDate.of(2026, 10, 26));
        // All time, but only projects worked in the last 3 days.
        var q = WorksiteQuery.of(null, null, null, null, 3, null, null);
        var p = page(q);
        assertTrue(ids(p).contains(FRESH));
        assertFalse(ids(p).contains(QUIET), "quiet since May");
    }
}
