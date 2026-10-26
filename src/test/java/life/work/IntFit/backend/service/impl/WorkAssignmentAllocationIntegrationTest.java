package life.work.IntFit.backend.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import life.work.IntFit.backend.dto.billing.WorksiteMonthlyCostDTO;
import life.work.IntFit.backend.dto.expenses.MasterWorksiteCostDetailsDTO;
import life.work.IntFit.backend.dto.expenses.WorksiteCostTotalsDTO;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.model.entity.TeamMember;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.repository.TeamMemberRepository;
import life.work.IntFit.backend.service.MonthlyCostService;
import life.work.IntFit.backend.service.MonthlyCostSql;
import life.work.IntFit.backend.service.expenses.WorksiteCostDetailsService;
import life.work.IntFit.backend.service.expenses.WorksiteCostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks for per-worksite hour allocation: HTTP contract, save
 * semantics, precision round trips and agreement between every backend
 * consumer (mapper, cost totals, cost details, monthly native SQL).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkAssignmentAllocationIntegrationTest {

    // These legacy full-context tests isolate allocation/rollback behavior. New MySQL-only
    // guards and revision/payroll races are exercised by the guarded MySQL reporting suite.
    // Do not create reporting tables or infer any datasource in these allocation fixtures.
    @org.springframework.boot.test.mock.mockito.MockBean
    private life.work.IntFit.backend.service.AssignmentConcurrency assignmentConcurrency;


    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);
    private static final String MONTH = "2026-09";
    private static final LocalDate MONTH_START = LocalDate.of(2026, 9, 1);
    private static final LocalDate MONTH_END = LocalDate.of(2026, 10, 1);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TeamMemberRepository members;
    @Autowired private MasterWorksiteRepository worksites;
    @Autowired private WorksiteCostService costService;
    @Autowired private WorksiteCostDetailsService detailsService;
    @Autowired private MonthlyCostService monthlyCostService;

    private long m1;
    private long m2;
    private long siteA;
    private long siteB;
    private long siteC;

    @BeforeEach
    void setUp() {
        m1 = member("Ahmad Saleh", 240.0);
        m2 = member("Ayham Khalil", 300.0);
        siteA = worksite("Site A");
        siteB = worksite("Site B");
        siteC = worksite("Site C");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from work_assignment_overtime");
        jdbc.update("delete from work_assignments");
        jdbc.update("delete from team_members");
        jdbc.update("delete from master_worksites");
    }

    // =====================================================================
    // Default behaviour
    // =====================================================================

    /** Old implementations, copied verbatim, to prove AUTO rows are unchanged. */
    private static final String LEGACY_MONTHLY_SQL = """
        WITH day_counts AS (
          SELECT wa.date, wa.team_member_id, COUNT(*) AS site_count
          FROM work_assignments wa
          WHERE wa.date >= :from AND wa.date < :to
          GROUP BY wa.date, wa.team_member_id
        ),
        member_day_cost AS (
          SELECT
            wa.master_worksite_id AS mw_id,
            ( tm.daily_wage
              + COALESCE(ot.overtime_hours, 0) * (tm.daily_wage / :stdHours) * :otMultiplier
            ) AS day_cost,
            dc.site_count
          FROM work_assignments wa
          JOIN team_members tm
            ON tm.id = wa.team_member_id
          LEFT JOIN work_assignment_overtime ot
            ON ot.team_member_id = wa.team_member_id
           AND ot.date = wa.date
          JOIN day_counts dc
            ON dc.date = wa.date
           AND dc.team_member_id = wa.team_member_id
          WHERE wa.date >= :from AND wa.date < :to
        )
        SELECT mw_id, ROUND(SUM(day_cost / NULLIF(site_count,0)), 2) AS sum_labor
        FROM member_day_cost
        WHERE mw_id IS NOT NULL
        GROUP BY mw_id
        """;

    @Test
    void automaticAllocationMatchesThePreviousCalculationsExactly() throws Exception {
        long m3 = member("Odd Wage", 233.33);
        long m4 = member("Half Day", 187.5);
        long m5 = member("Single Site", 410.0);
        save(day(
                List.of(a(m3, siteA), a(m3, siteB), a(m3, siteC), a(m4, siteA), a(m4, siteB), a(m5, siteA)),
                List.of(ot(m3, "1.7"), ot(m4, "0.35")),
                null));

        // Mapper: legacy round2((8 + ot) / n)
        for (JsonNode row : load()) {
            long memberId = row.get("teamMemberId").asLong();
            double ot = memberId == m3 ? 1.7 : memberId == m4 ? 0.35 : 0.0;
            int n = memberId == m3 ? 3 : memberId == m4 ? 2 : 1;
            double legacy = (8.0 + ot) / n;
            assertThat(row.get("allocatedHours").asDouble()).isEqualTo(Math.round(legacy * 100.0) / 100.0);
            assertThat(row.get("resolvedTotalHours").asDouble()).isEqualTo(legacy);
            assertThat(row.get("regularAllocationMode").asText()).isEqualTo("AUTO");
            assertThat(row.get("overtimeAllocationMode").asText()).isEqualTo("AUTO");
            assertThat(row.get("regularHoursOverride").isNull()).isTrue();
            assertThat(row.get("overtimeHoursOverride").isNull()).isTrue();
        }

        // Cost totals: legacy BigDecimal pipeline
        Map<Long, Double> wages = Map.of(m3, 233.33, m4, 187.5, m5, 410.0);
        Map<Long, Double> overtime = Map.of(m3, 1.7, m4, 0.35);
        Map<Long, Long> counts = Map.of(m3, 3L, m4, 2L, m5, 1L);
        for (long site : List.of(siteA, siteB, siteC)) {
            BigDecimal legacyHours = BigDecimal.ZERO;
            BigDecimal legacyWages = BigDecimal.ZERO;
            BigDecimal legacyDetails = BigDecimal.ZERO;
            for (long memberId : membersAt(site, Map.of(m3, List.of(siteA, siteB, siteC),
                    m4, List.of(siteA, siteB), m5, List.of(siteA)))) {
                double ot = overtime.getOrDefault(memberId, 0d);
                long n = counts.get(memberId);
                BigDecimal wage = BigDecimal.valueOf(wages.get(memberId));
                legacyHours = legacyHours.add(BigDecimal.valueOf(8d + ot)
                        .divide(BigDecimal.valueOf(n), 8, RoundingMode.HALF_UP));
                BigDecimal dayPay = wage.multiply(BigDecimal.valueOf(8d + ot)
                        .divide(BigDecimal.valueOf(8), 8, RoundingMode.HALF_UP));
                legacyWages = legacyWages.add(dayPay.divide(BigDecimal.valueOf(n), 8, RoundingMode.HALF_UP));
                BigDecimal allocated = BigDecimal.valueOf((8.0 + Math.max(0.0, ot)) / (double) n);
                legacyDetails = legacyDetails.add(wage.multiply(allocated)
                        .divide(BigDecimal.valueOf(8), 4, RoundingMode.HALF_UP));
            }
            WorksiteCostTotalsDTO totals = costService.computeTotals(site, DAY, DAY);
            assertThat(totals.getWorkers().getHours()).isEqualTo(legacyHours.setScale(2, RoundingMode.HALF_UP));
            assertThat(totals.getWorkers().getWages()).isEqualTo(legacyWages.setScale(0, RoundingMode.HALF_UP));
            assertThat(detailsService.getCostDetails(site, DAY, DAY).getTotals().getWorkers())
                    .isEqualTo(legacyDetails.setScale(2, RoundingMode.HALF_UP));
        }

        // Monthly native SQL: identical to the old query
        Map<Long, BigDecimal> legacyMonthly =
                runMonthlySql(MonthlyCostSql.withLiterals(LEGACY_MONTHLY_SQL, MONTH_START, MONTH_END));
        assertThat(legacyMonthly).hasSize(3);
        for (long site : List.of(siteA, siteB, siteC)) {
            assertThat(monthlyLabor(site)).isEqualTo(legacyMonthly.get(site));
        }
    }

    @Test
    void rawOvertimeWithoutAssignmentsKeepsItsBehaviour() throws Exception {
        save(day(List.of(a(m1, siteA)), List.of(ot(m1, "1"), ot(m2, "3")), null));

        assertThat(storedOvertime()).containsEntry(m2, 3.0).containsEntry(m1, 1.0);
        assertThat(load()).extracting(r -> r.get("teamMemberId").asLong()).containsExactly(m1);

        // omitting a member from overtime still deletes their row
        save(day(List.of(a(m1, siteA)), List.of(ot(m1, "1")), null));
        assertThat(storedOvertime()).containsOnlyKeys(m1);
    }

    @Test
    void wholeDayReadReturnsOvertimeOfWorkersWithoutAssignments() throws Exception {
        save(day(List.of(a(m1, siteA), a(m1, siteB)), List.of(ot(m1, "1.23456"), ot(m2, "3")),
                List.of(alloc(m1, null, custom(siteA, "1", siteB, "0.23456")))));

        JsonNode day = loadDay();
        assertThat(day.get("date").asText()).isEqualTo(DAY.toString());
        assertThat(day.get("assignments")).hasSize(2);
        assertThat(day.get("assignments").get(0).get("overtimeAllocationMode").asText()).isEqualTo("CUSTOM");
        JsonNode overtime = day.get("overtime");
        assertThat(overtime).hasSize(2);
        assertThat(overtime.get(0).get("teamMemberId").asLong()).isEqualTo(m1);
        assertThat(overtime.get(0).get("overtimeHoursDecimal").asText()).isEqualTo("1.23456");
        assertThat(overtime.get(0).get("hasAssignments").asBoolean()).isTrue();
        assertThat(overtime.get(1).get("teamMemberId").asLong()).isEqualTo(m2);
        assertThat(overtime.get(1).get("teamMemberName").asText()).isEqualTo("Ayham Khalil");
        assertThat(overtime.get(1).get("overtimeHours").asDouble()).isEqualTo(3.0);
        assertThat(overtime.get(1).get("hasAssignments").asBoolean()).isFalse();
        Snapshot before = snapshot();

        // echoing the whole-day read keeps everything, including m2's raw overtime
        save(echoDay(day));
        assertThat(snapshot()).isEqualTo(before);

        // echoing only the by-date rows (what the planners do today) drops it, as it always has
        save(echo(load(), false));
        assertThat(storedOvertime()).containsOnlyKeys(m1);
    }

    @Test
    void emptyDayReadHasEmptyLists() throws Exception {
        JsonNode day = loadDay();
        assertThat(day.get("assignments")).isEmpty();
        assertThat(day.get("overtime")).isEmpty();
    }

    // =====================================================================
    // AUTO / CUSTOM combinations — every consumer
    // =====================================================================

    @Test
    void allFourCombinationsResolveIndependentlyInEveryConsumer() throws Exception {
        long w = member("Odd Wage", 233.33);
        List<Map<String, Object>> assignments = List.of(a(w, siteA), a(w, siteB), a(w, siteC));
        List<Map<String, Object>> overtime = List.of(ot(w, "1.7"));
        Map<String, Object> regular = custom(siteA, "2.67", siteB, "2.67", siteC, "2.66");
        Map<String, Object> extra = custom(siteA, "0.3", siteB, "0.4", siteC, "1.0");

        // AUTO / AUTO: 233.33 * 9.7 / 8 / 3 = 94.304208333…
        save(day(assignments, overtime, List.of(alloc(w, auto(), auto()))));
        assertCombination(w, "AUTO", "AUTO",
                new double[]{8.0 / 3, 8.0 / 3, 8.0 / 3}, new double[]{1.7 / 3, 1.7 / 3, 1.7 / 3},
                new String[]{"94.30", "94.30", "94.30"}, new String[]{"94", "94", "94"});

        // CUSTOM regular / AUTO overtime: 94.4014291…, 94.4014291…, 94.1097666…
        save(day(assignments, overtime, List.of(alloc(w, regular, null))));
        assertCombination(w, "CUSTOM", "AUTO",
                new double[]{2.67, 2.67, 2.66}, new double[]{1.7 / 3, 1.7 / 3, 1.7 / 3},
                new String[]{"94.40", "94.40", "94.11"}, new String[]{"94", "94", "94"});

        // AUTO regular / CUSTOM overtime: 86.5265416…, 89.4431666…, 106.9429166…
        save(day(assignments, overtime, List.of(alloc(w, auto(), extra))));
        assertCombination(w, "AUTO", "CUSTOM",
                new double[]{8.0 / 3, 8.0 / 3, 8.0 / 3}, new double[]{0.3, 0.4, 1.0},
                new String[]{"86.53", "89.44", "106.94"}, new String[]{"87", "89", "107"});

        // CUSTOM / CUSTOM: 86.6237625, 89.5403875, 106.748475
        save(day(assignments, overtime, List.of(alloc(w, regular, null))));
        assertCombination(w, "CUSTOM", "CUSTOM",
                new double[]{2.67, 2.67, 2.66}, new double[]{0.3, 0.4, 1.0},
                new String[]{"86.62", "89.54", "106.75"}, new String[]{"87", "90", "107"});

        // Total pay is unchanged (233.33 * 9.7 / 8 = 282.912625) but separately rounded
        // site amounts follow each consumer's own rounding: 2dp sums to 282.91, whole shekels to 284.
        BigDecimal wholeShekels = BigDecimal.ZERO;
        BigDecimal twoDecimals = BigDecimal.ZERO;
        for (long site : List.of(siteA, siteB, siteC)) {
            wholeShekels = wholeShekels.add(costService.computeTotals(site, DAY, DAY).getWorkers().getWages());
            twoDecimals = twoDecimals.add(monthlyLabor(site));
        }
        assertThat(twoDecimals).isEqualByComparingTo("282.91");
        assertThat(wholeShekels).isEqualByComparingTo("284");
    }

    /**
     * Each report applies its own existing rounding to the exact amount. The 4-decimal
     * details report rounds 1.00495 to 1.0050 and then to 1.01; the monthly report rounds
     * the unrounded sum once, to 1.00. An automatic row with the same amount shows the
     * same difference, so it comes from the reports, not from custom allocation.
     */
    @Test
    void customRowsFollowEachReportsRoundingOfTheExactAmount() throws Exception {
        long w = member("Hourly Three", 24.0);
        save(day(List.of(a(w, siteA), a(w, siteB), a(w, siteC)), List.of(ot(w, "1")),
                List.of(alloc(w, custom(siteA, "0.00165", siteB, "4", siteC, "3.99835"), null))));
        // site A: 24/8 * (0.00165 + 1/3) = 1.00495 exactly
        assertThat(detailsService.getCostDetails(siteA, DAY, DAY).getTotals().getWorkers())
                .isEqualByComparingTo("1.01");
        assertThat(detailsService.getCostDetails(siteA, DAY, DAY).getDaily().get(0).getWorkerLines().get(0).getCost())
                .isEqualByComparingTo("1.01");
        assertThat(monthlyLabor(siteA)).isEqualByComparingTo("1.00");
        assertThat(costService.computeTotals(siteA, DAY, DAY).getWorkers().getWages()).isEqualByComparingTo("1");
        assertThat(costService.computeTotals(siteA, DAY, DAY).getWorkers().getHours()).isEqualByComparingTo("0.33");
        assertThat(detailsService.getCostDetails(siteC, DAY, DAY).getTotals().getWorkers())
                .isEqualByComparingTo("13.00");   // 12.99505

        // automatic row with the same kind of amount: 8/8 * (8 + 0.00495) = 8.00495
        long autoWorker = member("Hourly One", 8.0);
        long siteD = worksite("Site D");
        save(day(List.of(a(w, siteA), a(w, siteB), a(w, siteC), a(autoWorker, siteD)),
                List.of(ot(w, "1"), ot(autoWorker, "0.00495")), null));
        assertThat(detailsService.getCostDetails(siteD, DAY, DAY).getTotals().getWorkers())
                .isEqualByComparingTo("8.01");
        assertThat(monthlyLabor(siteD)).isEqualByComparingTo("8.00");
        assertThat(costService.computeTotals(siteD, DAY, DAY).getWorkers().getWages()).isEqualByComparingTo("8");
    }

    /**
     * An exact half cent (50.625) is rounded by the monthly SQL as a double, using the
     * database's own rule; custom and automatic rows reach the same double and the same result.
     */
    @Test
    void halfCentTiesMatchBetweenCustomAndAutomaticRows() throws Exception {
        long customWorker = member("Tie Custom", 100.0);
        long autoWorker = member("Tie Auto", 100.0);
        long siteD = worksite("Site D");
        save(day(List.of(a(customWorker, siteA), a(customWorker, siteB), a(autoWorker, siteC), a(autoWorker, siteD)),
                List.of(ot(customWorker, "0.1"), ot(autoWorker, "0.1")),
                List.of(alloc(customWorker, custom(siteA, "4", siteB, "4"), custom(siteA, "0.05", siteB, "0.05")))));

        assertThat(monthlyLabor(siteA)).isEqualTo(monthlyLabor(siteC)).isIn(
                new BigDecimal("50.62"), new BigDecimal("50.63"));
        assertThat(detailsService.getCostDetails(siteA, DAY, DAY).getTotals().getWorkers())
                .isEqualTo(detailsService.getCostDetails(siteC, DAY, DAY).getTotals().getWorkers())
                .isEqualByComparingTo("50.63");
        assertThat(costService.computeTotals(siteA, DAY, DAY).getWorkers().getWages())
                .isEqualTo(costService.computeTotals(siteC, DAY, DAY).getWorkers().getWages())
                .isEqualByComparingTo("51");
    }

    private void assertCombination(long memberId, String regularMode, String overtimeMode,
                                   double[] regular, double[] overtime,
                                   String[] twoDecimalCosts, String[] wholeCosts) throws Exception {
        long[] sites = {siteA, siteB, siteC};
        Map<Long, JsonNode> rows = rowsBySite(memberId);
        for (int i = 0; i < sites.length; i++) {
            JsonNode row = rows.get(sites[i]);
            assertThat(row.get("regularAllocationMode").asText()).isEqualTo(regularMode);
            assertThat(row.get("overtimeAllocationMode").asText()).isEqualTo(overtimeMode);
            assertThat(row.get("siteCount").asInt()).isEqualTo(3);
            assertThat(row.get("resolvedRegularHours").asDouble()).isCloseTo(regular[i], within());
            assertThat(row.get("resolvedOvertimeHours").asDouble()).isCloseTo(overtime[i], within());
            assertThat(row.get("resolvedTotalHours").asDouble()).isCloseTo(regular[i] + overtime[i], within());
            assertThat(row.get("allocatedHours").asDouble())
                    .isEqualTo(Math.round(row.get("resolvedTotalHours").asDouble() * 100.0) / 100.0);

            BigDecimal expected2dp = new BigDecimal(twoDecimalCosts[i]);
            MasterWorksiteCostDetailsDTO details = detailsService.getCostDetails(sites[i], DAY, DAY);
            assertThat(details.getTotals().getWorkers()).as("details site %d", i).isEqualTo(expected2dp);
            assertThat(monthlyLabor(sites[i])).as("monthly SQL site %d", i).isEqualTo(expected2dp);
            assertThat(costService.computeTotals(sites[i], DAY, DAY).getWorkers().getWages())
                    .as("cost totals site %d", i).isEqualTo(new BigDecimal(wholeCosts[i]));
        }
    }

    // =====================================================================
    // Omission, explicit reset, reconciliation
    // =====================================================================

    @Test
    void omittedAllocationsArePreservedByUnawareCallers() throws Exception {
        save(twoSiteDay("2", alloc(m1, custom(siteA, "6", siteB, "2"), custom(siteA, "0", siteB, "2"))));
        Map<String, BigDecimal[]> before = storedAllocations();

        // quick-assign style: same day re-submitted plus another worker, no allocations
        save(day(List.of(a(m1, siteA), a(m1, siteB), a(m2, siteA), a(m2, siteC)),
                List.of(ot(m1, "2")), null));

        Map<String, BigDecimal[]> after = storedAllocations();
        assertThat(after.get(m1 + ":" + siteA)).containsExactly(before.get(m1 + ":" + siteA));
        assertThat(after.get(m1 + ":" + siteB)).containsExactly(before.get(m1 + ":" + siteB));
        assertThat(after.get(m2 + ":" + siteA)).containsExactly(null, null);

        // m2 is AUTO/AUTO: worksite changes simply redistribute
        Map<Long, JsonNode> m2Rows = rowsBySite(m2);
        assertThat(m2Rows.get(siteA).get("allocatedHours").asDouble()).isEqualTo(4.0);
        assertThat(m2Rows.get(siteC).get("allocatedHours").asDouble()).isEqualTo(4.0);
    }

    @Test
    void explicitAutoResetsOnlyTheNamedCategory() throws Exception {
        save(twoSiteDay("2", alloc(m1, custom(siteA, "6", siteB, "2"), custom(siteA, "0", siteB, "2"))));

        save(twoSiteDay("2", alloc(m1, null, auto())));

        Map<Long, JsonNode> rows = rowsBySite(m1);
        assertThat(rows.get(siteA).get("regularAllocationMode").asText()).isEqualTo("CUSTOM");
        assertThat(rows.get(siteA).get("regularHoursOverride").asText()).isEqualTo("6");
        assertThat(rows.get(siteA).get("overtimeAllocationMode").asText()).isEqualTo("AUTO");
        assertThat(rows.get(siteA).get("resolvedTotalHours").asDouble()).isEqualTo(7.0);
        assertThat(rows.get(siteB).get("resolvedTotalHours").asDouble()).isEqualTo(3.0);
    }

    @Test
    void customEqualSplitRemainsDistinguishableFromAuto() throws Exception {
        save(twoSiteDay("0", alloc(m1, custom(siteA, "4", siteB, "4"), null)));
        JsonNode row = rowsBySite(m1).get(siteA);
        assertThat(row.get("regularAllocationMode").asText()).isEqualTo("CUSTOM");
        assertThat(row.get("regularHoursOverride").asText()).isEqualTo("4");
        assertThat(row.get("overtimeAllocationMode").asText()).isEqualTo("AUTO");
    }

    @Test
    void changedOvertimeTotalKeepsCustomRegularAndRejectsStaleCustomOvertime() throws Exception {
        save(twoSiteDay("2", alloc(m1, custom(siteA, "6", siteB, "2"), null)));
        save(twoSiteDay("5", null));                                // regular custom, overtime AUTO: fine
        assertThat(rowsBySite(m1).get(siteB).get("resolvedTotalHours").asDouble()).isEqualTo(4.5);

        save(twoSiteDay("5", alloc(m1, null, custom(siteA, "1", siteB, "4"))));
        Snapshot before = snapshot();

        JsonNode errors = reject(twoSiteDay("6", null));            // same worksites, new total
        assertError(errors, m1, "OVERTIME", "TOTAL_CHANGED");
        assertThat(errors.get(0).get("message").asText()).contains("adds up to 5h").contains("now 6h");
        assertThat(snapshot()).isEqualTo(before);

        save(twoSiteDay("6", alloc(m1, null, custom(siteA, "1", siteB, "5"))));  // reconciled
        assertThat(storedAllocations().get(m1 + ":" + siteB)[1]).isEqualByComparingTo("5");
    }

    @Test
    void worksiteIdentityChangesRequireReconciliation() throws Exception {
        save(twoSiteDay("2", alloc(m1, custom(siteA, "6", siteB, "2"), null)));
        Snapshot before = snapshot();

        // same count, different worksite
        JsonNode errors = reject(day(List.of(a(m1, siteA), a(m1, siteC)), List.of(ot(m1, "2")), null));
        assertError(errors, m1, "REGULAR", "WORKSITES_CHANGED");
        assertThat(errors.get(0).get("message").asText()).contains("Site B").contains("Site C");
        // an added worksite
        assertError(reject(day(List.of(a(m1, siteA), a(m1, siteB), a(m1, siteC)), List.of(ot(m1, "2")), null)),
                m1, "REGULAR", "WORKSITES_CHANGED");
        assertThat(snapshot()).isEqualTo(before);

        // valid replacement together with changed worksites and total
        save(day(List.of(a(m1, siteA), a(m1, siteC)), List.of(ot(m1, "3")),
                List.of(alloc(m1, custom(siteA, "5", siteC, "3"), custom(siteA, "1", siteC, "2")))));
        Map<String, BigDecimal[]> stored = storedAllocations();
        assertThat(stored).containsOnlyKeys(m1 + ":" + siteA, m1 + ":" + siteC);
        assertThat(stored.get(m1 + ":" + siteC)[0]).isEqualByComparingTo("3");
        assertThat(stored.get(m1 + ":" + siteC)[1]).isEqualByComparingTo("2");
    }

    @Test
    void reducingToOneSiteNeedsAnExplicitResolution() throws Exception {
        save(twoSiteDay("2", alloc(m1, custom(siteA, "6", siteB, "2"), custom(siteA, "0", siteB, "2"))));
        Snapshot before = snapshot();

        JsonNode errors = reject(day(List.of(a(m1, siteA)), List.of(ot(m1, "2")), null));
        assertError(errors, m1, "REGULAR", "WORKSITES_CHANGED");
        assertError(errors, m1, "OVERTIME", "WORKSITES_CHANGED");
        assertThat(snapshot()).isEqualTo(before);

        save(day(List.of(a(m1, siteA)), List.of(ot(m1, "2")),
                List.of(alloc(m1, custom(siteA, "8"), auto()))));
        JsonNode row = rowsBySite(m1).get(siteA);
        assertThat(row.get("regularAllocationMode").asText()).isEqualTo("CUSTOM");
        assertThat(row.get("regularHoursOverride").asText()).isEqualTo("8");
        assertThat(row.get("overtimeAllocationMode").asText()).isEqualTo("AUTO");
        assertThat(row.get("allocatedHours").asDouble()).isEqualTo(10.0);
    }

    @Test
    void removingAWorkerNeedsAnExplicitResetAndKeepsRawOvertimeRules() throws Exception {
        save(day(List.of(a(m1, siteA), a(m1, siteB), a(m2, siteA)),
                List.of(ot(m1, "2"), ot(m2, "1")),
                List.of(alloc(m1, custom(siteA, "6", siteB, "2"), null))));
        Snapshot before = snapshot();

        assertError(reject(day(List.of(a(m2, siteA)), List.of(ot(m1, "2"), ot(m2, "1")), null)),
                m1, "REGULAR", "NO_ASSIGNMENTS");
        assertThat(snapshot()).isEqualTo(before);

        save(day(List.of(a(m2, siteA)), List.of(ot(m1, "2"), ot(m2, "1")),
                List.of(alloc(m1, auto(), auto()))));
        assertThat(storedAllocations()).containsOnlyKeys(m2 + ":" + siteA);
        assertThat(storedOvertime()).containsEntry(m1, 2.0);   // raw overtime without assignments, as before
    }

    @Test
    void clearingTheDayRequiresExplicitResets() throws Exception {
        save(day(List.of(a(m1, siteA), a(m1, siteB), a(m2, siteA)),
                List.of(ot(m1, "2"), ot(m2, "1")),
                List.of(alloc(m1, custom(siteA, "6", siteB, "2"), custom(siteA, "1", siteB, "1")))));
        Snapshot before = snapshot();

        // clearing local state and sending an empty allocations list is not a reset
        assertError(reject(day(List.of(), List.of(), List.of())), m1, "OVERTIME", "NO_ASSIGNMENTS");
        assertThat(snapshot()).isEqualTo(before);

        // confirmed Clear Day: AUTO for every worker that was on the day, including AUTO ones
        save(day(List.of(), List.of(), List.of(alloc(m1, auto(), auto()), alloc(m2, auto(), auto()))));
        assertThat(storedAllocations()).isEmpty();
        assertThat(storedOvertime()).isEmpty();
    }

    // =====================================================================
    // Precision and round trips
    // =====================================================================

    @Test
    void existingOvertimePrecisionCanBeAllocatedAndReloadedExactly() throws Exception {
        save(twoSiteDay("1.23456", alloc(m1, null, custom(siteA, "0.61728", siteB, "0.61728"))));

        JsonNode row = rowsBySite(m1).get(siteA);
        assertThat(row.get("overtimeHoursDecimal").asText()).isEqualTo("1.23456");
        assertThat(row.get("overtimeHoursOverride").asText()).isEqualTo("0.61728");

        // a total stored as a double's shortest form, e.g. 0.1 + 0.2
        save(twoSiteDay("0.30000000000000004",
                alloc(m1, null, custom(siteA, "0.1", siteB, "0.20000000000000004"))));
        assertThat(rowsBySite(m1).get(siteB).get("overtimeHoursOverride").asText())
                .isEqualTo("0.20000000000000004");
    }

    @Test
    void valuesBeyondSixDecimalsAndFinerThanTheTotalRoundTripUntouched() throws Exception {
        Map<String, Object> regular = custom(
                siteA, "2.666666666666666666666666666667",
                siteB, "2.666666666666666666666666666667",
                siteC, "2.666666666666666666666666666666");
        save(day(List.of(a(m1, siteA), a(m1, siteB), a(m1, siteC)), List.of(ot(m1, "1")),
                List.of(alloc(m1, regular, custom(siteA, "0.3333333", siteB, "0.3333333", siteC, "0.3333334")))));
        Snapshot first = snapshot();

        List<JsonNode> rows = load();
        assertThat(rows).extracting(r -> r.get("regularHoursOverride").asText()).containsExactlyInAnyOrder(
                "2.666666666666666666666666666667", "2.666666666666666666666666666667",
                "2.666666666666666666666666666666");

        // what a client does when nothing was edited: send back exactly what it loaded
        save(echo(rows, true));
        assertThat(snapshot()).isEqualTo(first);
        // or omit allocations entirely
        save(echo(rows, false));
        assertThat(snapshot()).isEqualTo(first);
    }

    @Test
    void jsonNumbersAreParsedExactlyToo() throws Exception {
        String body = """
                {"date":"%s",
                 "assignments":[{"teamMemberId":%d,"masterWorksiteId":%d},{"teamMemberId":%d,"masterWorksiteId":%d}],
                 "overtime":[{"teamMemberId":%d,"overtimeHours":1.1}],
                 "allocations":[{"teamMemberId":%d,
                   "regular":{"mode":"CUSTOM","hours":[{"masterWorksiteId":%d,"hours":2.67},{"masterWorksiteId":%d,"hours":5.33}]},
                   "overtime":{"mode":"CUSTOM","hours":[{"masterWorksiteId":%d,"hours":0.1},{"masterWorksiteId":%d,"hours":1.0}]}}]}
                """.formatted(DAY, m1, siteA, m1, siteB, m1, m1, siteA, siteB, siteA, siteB);
        mvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        assertThat(rowsBySite(m1).get(siteA).get("regularHoursOverride").asText()).isEqualTo("2.67");
        assertThat(rowsBySite(m1).get(siteB).get("overtimeHoursOverride").asText()).isEqualTo("1");
    }

    @Test
    void overtimeTotalBeyondDoublePrecisionCannotBeCustomButStaysAcceptedForAuto() throws Exception {
        String total = "1.23456789012345678901";
        JsonNode errors = reject(twoSiteDay(total,
                alloc(m1, null, custom(siteA, "1", siteB, "0.23456789012345678901"))));
        assertError(errors, m1, "OVERTIME", "OVERTIME_TOTAL_NOT_REPRESENTABLE");
        assertThat(errors.get(0).get("message").asText()).contains("Use 1.2345678901234567 instead");

        save(twoSiteDay(total, null));
        assertThat(storedOvertime().get(m1)).isEqualTo(1.2345678901234567);
        assertThat(rowsBySite(m1).get(siteA).get("overtimeHoursDecimal").asText()).isEqualTo("1.2345678901234567");

        // the stored form can then be split exactly
        save(twoSiteDay("1.2345678901234567",
                alloc(m1, null, custom(siteA, "1", siteB, "0.2345678901234567"))));
    }

    @Test
    void excessPrecisionAndMalformedValuesAreRejected() throws Exception {
        assertError(reject(twoSiteDay("0", alloc(m1,
                        custom(siteA, "4.0000000000000000000000000000001", siteB, "3.9999999999999999999999999999999"),
                        null))),
                m1, "REGULAR", "UNSUPPORTED_PRECISION");
        assertError(reject(twoSiteDay("0", alloc(m1, custom(siteA, "8", siteA, "0"), null))),
                m1, "REGULAR", "DUPLICATE_WORKSITE");
        assertError(reject(twoSiteDay("0", alloc(m1, custom(siteA, "8.5", siteB, "-0.5"), null))),
                m1, "REGULAR", "NEGATIVE_HOURS");
        assertError(reject(twoSiteDay("0", alloc(m1, Map.of("mode", "Custom"), null))),
                m1, "REGULAR", "INVALID_MODE");
        assertThat(storedAllocations()).isEmpty();

        String notANumber = """
                {"date":"%s","assignments":[{"teamMemberId":%d,"masterWorksiteId":%d}],
                 "allocations":[{"teamMemberId":%d,"regular":{"mode":"CUSTOM","hours":[{"masterWorksiteId":%d,"hours":"eight"}]}}]}
                """.formatted(DAY, m1, siteA, m1, siteA);
        mvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON).content(notANumber))
                .andExpect(status().isBadRequest());
    }

    @Test
    void repeatedJsonKeysAreRejectedInsteadOfSilentlyOverwritten() throws Exception {
        String prefix = """
                {"date":"%s",
                 "assignments":[{"teamMemberId":%d,"masterWorksiteId":%d},{"teamMemberId":%d,"masterWorksiteId":%d}],
                 "overtime":[{"teamMemberId":%d,"overtimeHours":2}],
                """.formatted(DAY, m1, siteA, m1, siteB, m1);
        String regular = """
                {"mode":"CUSTOM","hours":[{"masterWorksiteId":%d,"hours":"6"},{"masterWorksiteId":%d,"hours":"2"}]}
                """.formatted(siteA, siteB);

        JsonNode errors = rejectRaw(prefix + """
                "allocations":[{"teamMemberId":%d,"regular":%s,"regular":{"mode":"AUTO"}}]}
                """.formatted(m1, regular));
        assertError(errors, m1, "REGULAR", "DUPLICATE_FIELD");
        assertThat(errors.get(0).get("message").asText()).contains("\"regular\"");

        errors = rejectRaw(prefix + """
                "allocations":[{"teamMemberId":%d,"overtime":{"mode":"CUSTOM","hours":[
                  {"masterWorksiteId":%d,"hours":"2","hours":"0"},{"masterWorksiteId":%d,"hours":"0"}]}}]}
                """.formatted(m1, siteA, siteB));
        assertError(errors, m1, "OVERTIME", "DUPLICATE_FIELD");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("message").asText()).contains("overtime.hours[0].hours");

        errors = rejectRaw(prefix + """
                "allocations":[{"teamMemberId":%d,"regular":%s}],
                "allocations":[]}
                """.formatted(m1, regular));
        assertThat(errors.get(0).get("code").asText()).isEqualTo("DUPLICATE_FIELD");
        assertThat(errors.get(0).get("message").asText()).contains("\"allocations\"");

        assertThat(storedAllocations()).isEmpty();
        assertThat(storedOvertime()).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void extremeMagnitudesFailFastWithoutChangingData() throws Exception {
        save(twoSiteDay("2", null));
        Snapshot before = snapshot();

        assertError(reject(twoSiteDay("2", alloc(m1,
                        custom(siteA, "1E+1000000000", siteB, "1E-1000000000"), null))),
                m1, "REGULAR", "UNSUPPORTED_PRECISION");
        assertError(reject(twoSiteDay("1E+1000000000", alloc(m1, null, custom(siteA, "1", siteB, "1")))),
                m1, "OVERTIME", "OVERTIME_TOTAL_NOT_REPRESENTABLE");
        // a positive stored total too small for 30 decimal places cannot be split
        assertError(reject(twoSiteDay("1E-40", alloc(m1, null, custom(siteA, "0", siteB, "0")))),
                m1, "OVERTIME", "OVERTIME_TOTAL_UNSUPPORTED");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void rejectionResponseIsActionable() throws Exception {
        save(twoSiteDay("2", alloc(m1, custom(siteA, "6", siteB, "2"), null)));

        MvcResult result = mvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(day(List.of(a(m1, siteA)), List.of(), null))))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(422);
        assertThat(body.get("error").asText()).isEqualTo("ALLOCATION_VALIDATION_FAILED");
        assertThat(body.get("message").asText()).contains("not saved").contains("planner");
        JsonNode error = body.get("errors").get(0);
        assertThat(error.get("teamMemberId").asLong()).isEqualTo(m1);
        assertThat(error.get("teamMemberName").asText()).isEqualTo("Ahmad Saleh");
        assertThat(error.get("category").asText()).isEqualTo("REGULAR");
        assertThat(error.get("code").asText()).isEqualTo("WORKSITES_CHANGED");
        assertThat(error.get("message").asText()).contains("Ahmad Saleh").contains("planner");
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private record Snapshot(Map<String, List<String>> assignments, Map<Long, Double> overtime) {
    }

    private Snapshot snapshot() {
        Map<String, List<String>> rows = new TreeMap<>();
        jdbc.query("select team_member_id, master_worksite_id, regular_hours_override, overtime_hours_override "
                + "from work_assignments where date = ?", rs -> {
            String key = rs.getLong(1) + ":" + rs.getLong(2);
            rows.computeIfAbsent(key, k -> new ArrayList<>()).add(
                    plain(rs.getBigDecimal(3)) + "|" + plain(rs.getBigDecimal(4)));
        }, DAY);
        return new Snapshot(rows, storedOvertime());
    }

    private Map<String, BigDecimal[]> storedAllocations() {
        Map<String, BigDecimal[]> rows = new HashMap<>();
        jdbc.query("select team_member_id, master_worksite_id, regular_hours_override, overtime_hours_override "
                + "from work_assignments where date = ?", rs -> {
            rows.put(rs.getLong(1) + ":" + rs.getLong(2),
                    new BigDecimal[]{rs.getBigDecimal(3), rs.getBigDecimal(4)});
        }, DAY);
        return rows;
    }

    private Map<Long, Double> storedOvertime() {
        Map<Long, Double> rows = new TreeMap<>();
        jdbc.query("select team_member_id, overtime_hours from work_assignment_overtime where date = ?",
                rs -> {
                    rows.put(rs.getLong(1), rs.getDouble(2));
                }, DAY);
        return rows;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    /** Monthly labor for a site: through the service on MySQL, through the same SQL text on H2. */
    private BigDecimal monthlyLabor(long site) {
        if (!isH2()) {
            return monthlyCostService.calculateMonthlyCosts(MONTH, site, 15.0).getRows().stream()
                    .map(WorksiteMonthlyCostDTO::getLaborCost)
                    .findFirst()
                    .orElseThrow();
        }
        return runMonthlySql(MonthlyCostSql.laborSumsWithLiterals(MONTH_START, MONTH_END))
                .getOrDefault(site, BigDecimal.ZERO.setScale(2));
    }

    /** Same result conversion as MonthlyCostService.loadLaborSums. */
    private Map<Long, BigDecimal> runMonthlySql(String sql) {
        Map<Long, BigDecimal> sums = new HashMap<>();
        jdbc.query(sql, rs -> {
            Object value = rs.getObject(2);
            BigDecimal sum = value instanceof BigDecimal bd ? bd : BigDecimal.valueOf(((Number) value).doubleValue());
            sums.put(rs.getLong(1), sum.setScale(2, RoundingMode.HALF_UP));
        });
        return sums;
    }

    private boolean isH2() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                c.getMetaData().getDatabaseProductName().toLowerCase().contains("h2")));
    }

    private void save(Map<String, Object> body) throws Exception {
        MvcResult result = mvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(204);
    }

    private JsonNode reject(Map<String, Object> body) throws Exception {
        MvcResult result = mvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("errors");
    }

    private JsonNode rejectRaw(String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("errors");
    }

    private JsonNode loadDay() throws Exception {
        MvcResult result = mvc.perform(get("/api/assignments/day").param("date", DAY.toString()))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Rebuilds a save request from the whole-day read, sending every value back unchanged. */
    private static Map<String, Object> echoDay(JsonNode day) {
        List<JsonNode> rows = new ArrayList<>();
        day.get("assignments").forEach(rows::add);
        Map<String, Object> body = echo(rows, true);
        List<Map<String, Object>> overtime = new ArrayList<>();
        day.get("overtime").forEach(o ->
                overtime.add(ot(o.get("teamMemberId").asLong(), o.get("overtimeHoursDecimal").asText())));
        body.put("overtime", overtime);
        return body;
    }

    private static void assertError(JsonNode errors, long memberId, String category, String code) {
        List<String> seen = new ArrayList<>();
        for (JsonNode e : errors) {
            seen.add(e.get("teamMemberId") + "/" + e.get("category").asText() + "/" + e.get("code").asText());
            if (e.get("teamMemberId").asLong() == memberId
                    && category.equals(e.get("category").asText())
                    && code.equals(e.get("code").asText())) {
                return;
            }
        }
        throw new AssertionError("Expected " + memberId + "/" + category + "/" + code + " in " + seen);
    }

    private List<JsonNode> load() throws Exception {
        MvcResult result = mvc.perform(get("/api/assignments/by-date").param("date", DAY.toString()))
                .andExpect(status().isOk())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {
        });
    }

    private Map<Long, JsonNode> rowsBySite(long memberId) throws Exception {
        Map<Long, JsonNode> rows = new HashMap<>();
        for (JsonNode row : load()) {
            if (row.get("teamMemberId").asLong() == memberId) {
                rows.put(row.get("masterWorksiteId").asLong(), row);
            }
        }
        return rows;
    }

    /** Rebuilds a save request from a GET response, as an unmodified client would. */
    private static Map<String, Object> echo(List<JsonNode> rows, boolean includeAllocations) {
        List<Map<String, Object>> assignments = new ArrayList<>();
        Map<Long, String> overtime = new LinkedHashMap<>();
        Map<Long, List<Object>> regular = new LinkedHashMap<>();
        Map<Long, List<Object>> extra = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            long memberId = row.get("teamMemberId").asLong();
            long site = row.get("masterWorksiteId").asLong();
            assignments.add(a(memberId, site));
            if (!row.get("overtimeHoursDecimal").isNull()) {
                overtime.put(memberId, row.get("overtimeHoursDecimal").asText());
            }
            if ("CUSTOM".equals(row.get("regularAllocationMode").asText())) {
                regular.computeIfAbsent(memberId, k -> new ArrayList<>())
                        .addAll(List.of(site, row.get("regularHoursOverride").asText()));
            }
            if ("CUSTOM".equals(row.get("overtimeAllocationMode").asText())) {
                extra.computeIfAbsent(memberId, k -> new ArrayList<>())
                        .addAll(List.of(site, row.get("overtimeHoursOverride").asText()));
            }
        }
        List<Map<String, Object>> allocations = new ArrayList<>();
        if (includeAllocations) {
            Set<Long> ids = new LinkedHashSet<>(regular.keySet());
            ids.addAll(extra.keySet());
            for (Long id : ids) {
                allocations.add(alloc(id,
                        regular.containsKey(id) ? custom(regular.get(id).toArray()) : auto(),
                        extra.containsKey(id) ? custom(extra.get(id).toArray()) : auto()));
            }
        }
        List<Map<String, Object>> ot = overtime.entrySet().stream().map(e -> ot(e.getKey(), e.getValue())).toList();
        return day(assignments, ot, includeAllocations ? allocations : null);
    }

    private Map<String, Object> twoSiteDay(String overtimeHours, Map<String, Object> allocation) {
        return day(List.of(a(m1, siteA), a(m1, siteB)),
                List.of(ot(m1, overtimeHours)),
                allocation == null ? null : List.of(allocation));
    }

    private static Map<String, Object> day(List<Map<String, Object>> assignments,
                                           List<Map<String, Object>> overtime,
                                           List<Map<String, Object>> allocations) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", DAY.toString());
        body.put("assignments", assignments);
        body.put("overtime", overtime);
        if (allocations != null) {
            body.put("allocations", allocations);
        }
        return body;
    }

    private static Map<String, Object> a(long memberId, long siteId) {
        return Map.of("teamMemberId", memberId, "masterWorksiteId", siteId);
    }

    private static Map<String, Object> ot(long memberId, String hours) {
        return Map.of("teamMemberId", memberId, "overtimeHours", hours);
    }

    private static Map<String, Object> alloc(long memberId, Map<String, Object> regular, Map<String, Object> overtime) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("teamMemberId", memberId);
        if (regular != null) entry.put("regular", regular);
        if (overtime != null) entry.put("overtime", overtime);
        return entry;
    }

    private static Map<String, Object> auto() {
        return Map.of("mode", "AUTO");
    }

    /** Alternating worksite id and hours string; hours are sent as JSON strings. */
    private static Map<String, Object> custom(Object... siteThenHours) {
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int i = 0; i < siteThenHours.length; i += 2) {
            hours.add(Map.of("masterWorksiteId", siteThenHours[i], "hours", siteThenHours[i + 1]));
        }
        return Map.of("mode", "CUSTOM", "hours", hours);
    }

    private static List<Long> membersAt(long site, Map<Long, List<Long>> sitesByMember) {
        return sitesByMember.entrySet().stream()
                .filter(e -> e.getValue().contains(site))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-12);
    }

    private long member(String name, double wage) {
        TeamMember tm = new TeamMember();
        tm.setName(name);
        tm.setRole("Painter");
        tm.setDailyWage(wage);
        return members.save(tm).getId();
    }

    private long worksite(String name) {
        return worksites.save(MasterWorksite.builder().approvedName(name).build()).getId();
    }
}
