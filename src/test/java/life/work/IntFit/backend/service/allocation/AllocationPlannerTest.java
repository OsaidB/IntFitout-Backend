package life.work.IntFit.backend.service.allocation;

import life.work.IntFit.backend.dto.daily_assignments.AllocationErrorDTO;
import life.work.IntFit.backend.dto.daily_assignments.CategoryAllocationDTO;
import life.work.IntFit.backend.dto.daily_assignments.MemberAllocationDTO;
import life.work.IntFit.backend.dto.daily_assignments.SiteHoursDTO;
import life.work.IntFit.backend.exception.error.AllocationValidationException;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.model.entity.TeamMember;
import life.work.IntFit.backend.model.entity.WorkAssignment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure rule checks for merging and validating a day's allocations. */
class AllocationPlannerTest {

    private static final long M = 1L;
    private static final long A = 10L;
    private static final long B = 20L;
    private static final long C = 30L;

    // ---------- merge semantics ----------

    @Test
    void autoMembersNeedNoAllocationEntries() {
        AllocationPlanner.Plan plan = plan(sites(M, A, B), ot(), List.of(), null);
        assertThat(plan.regular()).isEmpty();
        assertThat(plan.overtime()).isEmpty();
    }

    @Test
    void omittedCategoriesPreserveStoredValuesIndependently() {
        List<WorkAssignment> stored = List.of(row(M, A, "6", "0"), row(M, B, "2", "2"));
        MemberAllocationDTO onlyRegular = member(M, custom(A, "5", B, "3"), null);

        AllocationPlanner.Plan plan = plan(sites(M, A, B), ot(M, "2"), stored, List.of(onlyRegular));

        assertThat(plan.regularFor(M, A)).isEqualByComparingTo("5");
        assertThat(plan.regularFor(M, B)).isEqualByComparingTo("3");
        assertThat(plan.overtimeFor(M, A)).isEqualByComparingTo("0");
        assertThat(plan.overtimeFor(M, B)).isEqualByComparingTo("2");
    }

    @Test
    void explicitAutoResetsOnlyThatCategory() {
        List<WorkAssignment> stored = List.of(row(M, A, "6", "0"), row(M, B, "2", "2"));
        MemberAllocationDTO resetOvertime = member(M, null, auto());

        AllocationPlanner.Plan plan = plan(sites(M, A, B), ot(M, "2"), stored, List.of(resetOvertime));

        assertThat(plan.regularFor(M, A)).isEqualByComparingTo("6");
        assertThat(plan.overtime()).doesNotContainKey(M);
    }

    @Test
    void customEqualSplitStaysCustom() {
        AllocationPlanner.Plan plan = plan(sites(M, A, B), ot(), List.of(),
                List.of(member(M, custom(A, "4", B, "4"), null)));
        assertThat(plan.regular()).containsKey(M);
    }

    // ---------- preserved values checked against the new day ----------

    @Test
    void changedOvertimeTotalKeepsValidCustomRegularSplit() {
        List<WorkAssignment> stored = List.of(row(M, A, "6", null), row(M, B, "2", null));
        AllocationPlanner.Plan plan = plan(sites(M, A, B), ot(M, "5"), stored, null);
        assertThat(plan.regularFor(M, A)).isEqualByComparingTo("6");
    }

    @Test
    void changedOvertimeTotalRejectsPreservedCustomOvertimeOnUnchangedSites() {
        List<WorkAssignment> stored = List.of(row(M, A, null, "0"), row(M, B, null, "2"));
        assertRejected(() -> plan(sites(M, A, B), ot(M, "3"), stored, null),
                "OVERTIME", "TOTAL_CHANGED");
    }

    @Test
    void swappedWorksiteWithSameCountRejectsPreservedSplit() {
        List<WorkAssignment> stored = List.of(row(M, A, "6", null), row(M, B, "2", null));
        assertRejected(() -> plan(sites(M, A, C), ot(), stored, null), "REGULAR", "WORKSITES_CHANGED");
    }

    @Test
    void reducingToOneOrZeroSitesRejectsPreservedSplit() {
        List<WorkAssignment> stored = List.of(row(M, A, "6", null), row(M, B, "2", null));
        assertRejected(() -> plan(sites(M, A), ot(), stored, null), "REGULAR", "WORKSITES_CHANGED");
        assertRejected(() -> plan(Map.of(), ot(), stored, null), "REGULAR", "NO_ASSIGNMENTS");
    }

    @Test
    void explicitAutoAllowsRemovingAWorkerEntirely() {
        List<WorkAssignment> stored = List.of(row(M, A, "6", "1"), row(M, B, "2", "0"));
        AllocationPlanner.Plan plan = plan(Map.of(), ot(), stored, List.of(member(M, auto(), auto())));
        assertThat(plan.regular()).isEmpty();
        assertThat(plan.overtime()).isEmpty();
    }

    @Test
    void validReplacementIsAcceptedWithChangedSitesAndTotal() {
        List<WorkAssignment> stored = List.of(row(M, A, "6", "1"), row(M, B, "2", "0"));
        AllocationPlanner.Plan plan = plan(sites(M, A, C), ot(M, "3"), stored,
                List.of(member(M, custom(A, "7", C, "1"), custom(A, "0.5", C, "2.5"))));
        assertThat(plan.regularFor(M, C)).isEqualByComparingTo("1");
        assertThat(plan.overtimeFor(M, C)).isEqualByComparingTo("2.5");
    }

    @Test
    void partialStoredStateIsNotSilentlyCompleted() {
        List<WorkAssignment> stored = List.of(row(M, A, "8", null), row(M, B, null, null));
        assertRejected(() -> plan(sites(M, A, B), ot(), stored, null), "REGULAR", "WORKSITES_CHANGED");
    }

    @Test
    void conflictingStoredDuplicatesAreRejected() {
        List<WorkAssignment> stored = List.of(row(M, A, "6", null), row(M, A, "5", null), row(M, B, "2", null));
        assertRejected(() -> plan(sites(M, A, B), ot(), stored, null), "REGULAR", "STORED_ALLOCATION_CONFLICT");
    }

    // ---------- submitted CUSTOM validation ----------

    @Test
    void customMustCoverExactlyTheAssignedSites() {
        assertRejected(() -> plan(sites(M, A, B), ot(), List.of(), List.of(member(M, custom(A, "8"), null))),
                "REGULAR", "COVERAGE_MISMATCH");
        assertRejected(() -> plan(sites(M, A), ot(), List.of(), List.of(member(M, custom(A, "4", B, "4"), null))),
                "REGULAR", "COVERAGE_MISMATCH");
        assertRejected(() -> plan(Map.of(), ot(), List.of(), List.of(member(M, custom(A, "8"), null))),
                "REGULAR", "NO_ASSIGNMENTS");
    }

    @Test
    void customMustSumExactly() {
        assertRejected(() -> plan(sites(M, A, B, C), ot(), List.of(),
                        List.of(member(M, custom(A, "2.67", B, "2.67", C, "2.67"), null))),
                "REGULAR", "SUM_MISMATCH");
        assertRejected(() -> plan(sites(M, A, B), ot(M, "2"), List.of(),
                        List.of(member(M, null, custom(A, "1", B, "0.999999999999999999999999999999")))),
                "OVERTIME", "SUM_MISMATCH");
        assertRejected(() -> plan(sites(M, A, B), ot(), List.of(),
                        List.of(member(M, null, custom(A, "1", B, "0")))),
                "OVERTIME", "SUM_MISMATCH");
    }

    @Test
    void finerThanTotalAndLongFractionsAreAccepted() {
        AllocationPlanner.Plan plan = plan(sites(M, A, B, C), ot(M, "1.23456"), List.of(), List.of(member(M,
                custom(A, "2.67", B, "2.67", C, "2.66"),
                custom(A, "0.411520000000000000000000000001", B, "0.411519999999999999999999999999", C, "0.41152"))));
        assertThat(plan.overtimeFor(M, A)).isEqualByComparingTo("0.411520000000000000000000000001");
    }

    @Test
    void overtimeTotalThatCannotBeStoredCannotBeCustom() {
        assertRejected(() -> plan(sites(M, A, B), ot(M, "1.23456789012345678901"), List.of(),
                        List.of(member(M, null, custom(A, "1", B, "0.23456789012345678901")))),
                "OVERTIME", "OVERTIME_TOTAL_NOT_REPRESENTABLE");
    }

    @Test
    void malformedRequestsAreRejected() {
        Map<Long, Set<Long>> s = sites(M, A, B);
        assertRejected(() -> plan(s, ot(), List.of(), List.of(member(M, cat(null), null))), "REGULAR", "MISSING_MODE");
        assertRejected(() -> plan(s, ot(), List.of(), List.of(member(M, cat("custom"), null))), "REGULAR", "INVALID_MODE");
        assertRejected(() -> plan(s, ot(), List.of(), List.of(member(M,
                new CategoryAllocationDTO("AUTO", List.of(hours(A, "8"))), null))), "REGULAR", "AUTO_WITH_HOURS");
        assertRejected(() -> plan(s, ot(), List.of(), List.of(member(M, cat("CUSTOM"), null))), "REGULAR", "EMPTY_ALLOCATION");
        assertRejected(() -> plan(s, ot(), List.of(), List.of(member(M,
                new CategoryAllocationDTO("CUSTOM", Arrays.asList(hours(A, "8"), null)), null))), "REGULAR", "INVALID_ENTRY");
        assertRejected(() -> plan(s, ot(), List.of(), List.of(member(M,
                new CategoryAllocationDTO("CUSTOM", List.of(hours(A, "8"), new SiteHoursDTO(B, null))), null))),
                "REGULAR", "MISSING_HOURS");
        assertRejected(() -> plan(s, ot(), List.of(), List.of(member(M, custom(A, "9", B, "-1"), null))),
                "REGULAR", "NEGATIVE_HOURS");
        assertRejected(() -> plan(s, ot(), List.of(), List.of(member(M,
                custom(A, "4.0000000000000000000000000000001", B, "3.9999999999999999999999999999999"), null))),
                "REGULAR", "UNSUPPORTED_PRECISION");
        assertRejected(() -> plan(s, ot(), List.of(), List.of(member(M,
                custom(A, "4", A, "2", B, "2"), null))), "REGULAR", "DUPLICATE_WORKSITE");
        assertRejected(() -> plan(s, ot(), List.of(), List.of(member(M, auto(), null), member(M, auto(), null))),
                null, "DUPLICATE_MEMBER");
        assertRejected(() -> plan(s, ot(), List.of(), Arrays.asList((MemberAllocationDTO) null)),
                null, "INVALID_ALLOCATION");
    }

    @Test
    void entriesWithRepeatedKeysAreRejectedWithoutFurtherChecks() {
        MemberAllocationDTO entry = member(M, custom(A, "1", B, "1"), null);
        entry.setDuplicateFields(List.of("regular.hours[1].hours"));
        assertThatThrownBy(() -> plan(sites(M, A, B), ot(), List.of(), List.of(entry)))
                .isInstanceOfSatisfying(AllocationValidationException.class, ex -> {
                    assertThat(ex.getErrors()).singleElement().satisfies(e -> {
                        assertThat(e.code()).isEqualTo("DUPLICATE_FIELD");
                        assertThat(e.category()).isEqualTo("REGULAR");
                    });
                });
    }

    @Test
    void omittedOtherCategoryIsStillCheckedWhenOneIsSupplied() {
        List<WorkAssignment> stored = List.of(row(M, A, null, "0"), row(M, B, null, "2"));
        assertRejected(() -> plan(sites(M, A, B), ot(M, "3"), stored,
                        List.of(member(M, custom(A, "4", B, "4"), null))),
                "OVERTIME", "TOTAL_CHANGED");
    }

    @Test
    void unsupportedOvertimeTotalsFailBeforeValuesAreChecked() {
        assertRejected(() -> plan(sites(M, A, B), ot(M, "1E-40"), List.of(),
                        List.of(member(M, null, custom(A, "0", B, "1E-40")))),
                "OVERTIME", "OVERTIME_TOTAL_UNSUPPORTED");
        List<WorkAssignment> stored = List.of(row(M, A, null, "1"), row(M, B, null, "1"));
        assertRejected(() -> plan(sites(M, A, B), ot(M, "2.00000000000000000001"), stored, null),
                "OVERTIME", "OVERTIME_TOTAL_NOT_REPRESENTABLE");
        // automatic overtime is unaffected by the total's precision
        assertThat(plan(sites(M, A, B), ot(M, "1E-40"), List.of(), null).overtime()).isEmpty();
    }

    @Test
    void extremeExponentsAreRejectedCheaply() {
        assertRejected(() -> plan(sites(M, A, B), ot(), List.of(),
                        List.of(member(M, custom(A, "1E+1000000000", B, "0"), null))),
                "REGULAR", "UNSUPPORTED_PRECISION");
    }

    @Test
    void allErrorsAreReportedTogether() {
        long other = 2L;
        Map<Long, Set<Long>> s = new HashMap<>(sites(M, A, B));
        s.put(other, Set.of(A, B));
        List<WorkAssignment> stored = List.of(row(other, A, "6", null), row(other, C, "2", null));

        assertThatThrownBy(() -> plan(s, ot(), stored, List.of(member(M, custom(A, "1", B, "1"), null))))
                .isInstanceOfSatisfying(AllocationValidationException.class, ex -> {
                    assertThat(ex.getErrors()).extracting(AllocationErrorDTO::teamMemberId)
                            .containsExactly(M, other);
                    assertThat(ex.getErrors().get(0).teamMemberName()).isEqualTo("Worker One");
                    assertThat(ex.getMessage()).contains("2 workers").contains("planner");
                });
    }

    // ---------- helpers ----------

    private static AllocationPlanner.Plan plan(Map<Long, Set<Long>> sites, Map<Long, BigDecimal> overtime,
                                               List<WorkAssignment> stored, List<MemberAllocationDTO> requests) {
        return AllocationPlanner.plan(new AllocationPlanner.Input(sites, overtime, stored, requests,
                Map.of(M, "Worker One"), Map.of(A, "Site A", B, "Site B", C, "Site C")));
    }

    private static void assertRejected(Runnable call, String category, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(AllocationValidationException.class, ex ->
                        assertThat(ex.getErrors())
                                .anySatisfy(e -> {
                                    assertThat(e.code()).isEqualTo(code);
                                    assertThat(e.category()).isEqualTo(category);
                                    assertThat(e.message()).isNotBlank();
                                }));
    }

    private static Map<Long, Set<Long>> sites(long member, Long... worksites) {
        return Map.of(member, new HashSet<>(Arrays.asList(worksites)));
    }

    private static Map<Long, BigDecimal> ot() {
        return Map.of();
    }

    private static Map<Long, BigDecimal> ot(long member, String hours) {
        return Map.of(member, new BigDecimal(hours));
    }

    private static WorkAssignment row(long member, long worksite, String regular, String overtime) {
        TeamMember tm = new TeamMember();
        tm.setId(member);
        return WorkAssignment.builder()
                .teamMember(tm)
                .masterWorksite(MasterWorksite.builder().id(worksite).build())
                .date(LocalDate.of(2026, 9, 10))
                .regularHoursOverride(regular == null ? null : new BigDecimal(regular))
                .overtimeHoursOverride(overtime == null ? null : new BigDecimal(overtime))
                .build();
    }

    private static MemberAllocationDTO member(long id, CategoryAllocationDTO regular, CategoryAllocationDTO overtime) {
        return new MemberAllocationDTO(id, regular, overtime);
    }

    private static CategoryAllocationDTO auto() {
        return new CategoryAllocationDTO("AUTO", null);
    }

    private static CategoryAllocationDTO cat(String mode) {
        return new CategoryAllocationDTO(mode, List.of());
    }

    private static CategoryAllocationDTO custom(Object... siteThenHours) {
        List<SiteHoursDTO> list = new ArrayList<>();
        for (int i = 0; i < siteThenHours.length; i += 2) {
            list.add(hours((Long) siteThenHours[i], (String) siteThenHours[i + 1]));
        }
        return new CategoryAllocationDTO("CUSTOM", list);
    }

    private static SiteHoursDTO hours(long worksite, String hours) {
        return new SiteHoursDTO(worksite, new BigDecimal(hours));
    }
}
