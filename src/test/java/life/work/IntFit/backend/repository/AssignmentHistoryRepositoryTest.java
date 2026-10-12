package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.model.entity.TeamMember;
import life.work.IntFit.backend.model.entity.WorkAssignment;
import life.work.IntFit.backend.model.entity.WorkAssignmentOvertime;
import life.work.IntFit.backend.repository.projection.SiteCountView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 tests for the two queries behind GET /api/assignments/history.
 *
 * These run the real JPQL, which the mocked service test cannot: they are what
 * catches a wrong grouping or a typo in the aggregate. The service layer turns
 * siteCountsByRange into distinct-worker counts, so what matters here is that
 * the query emits exactly one row per (date, member) however many worksites
 * that member is on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AssignmentHistoryRepositoryTest {

    @Autowired
    private WorkAssignmentRepository repository;

    @Autowired
    private TestEntityManager em;

    private TeamMember member(String name) {
        TeamMember m = new TeamMember();
        m.setName(name);
        m.setRole("worker");
        m.setDailyWage(210.0);
        return em.persist(m);
    }

    private MasterWorksite worksite(String approvedName) {
        MasterWorksite w = new MasterWorksite();
        w.setApprovedName(approvedName);
        return em.persist(w);
    }

    private void assign(TeamMember m, MasterWorksite w, String date) {
        em.persist(WorkAssignment.builder()
                .teamMember(m)
                .masterWorksite(w)
                .date(LocalDate.parse(date))
                .build());
    }

    /** date -> distinct members, exactly as the service derives it. */
    private Map<LocalDate, Long> workersByDate(String from, String to) {
        List<SiteCountView> rows =
                repository.siteCountsByRange(LocalDate.parse(from), LocalDate.parse(to));
        return rows.stream().collect(Collectors.groupingBy(
                SiteCountView::getDate, Collectors.counting()));
    }

    @Test
    void groupsOneRowPerMemberPerDateEvenAcrossSeveralWorksites() {
        TeamMember ahmad = member("Ahmad");
        TeamMember sami = member("Sami");
        MasterWorksite villa = worksite("Villa");
        MasterWorksite office = worksite("Office");

        // Ahmad on two worksites the same day; Sami on one.
        assign(ahmad, villa, "2026-09-19");
        assign(ahmad, office, "2026-09-19");
        assign(sami, villa, "2026-09-19");
        // Ahmad alone the next day, on two worksites again.
        assign(ahmad, villa, "2026-09-20");
        assign(ahmad, office, "2026-09-20");
        em.flush();

        List<SiteCountView> rows = repository.siteCountsByRange(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        // Three (date, member) pairs from five assignment rows.
        assertThat(rows).hasSize(3);
        assertThat(workersByDate("2026-09-01", "2026-09-30")).containsExactlyInAnyOrderEntriesOf(
                Map.of(LocalDate.of(2026, 9, 19), 2L, LocalDate.of(2026, 9, 20), 1L));

        // The per-pair site count is still right, which is what hour splitting uses.
        Map<LocalDate, Long> sitesForAhmad = rows.stream()
                .filter(r -> r.getTeamMemberId().equals(ahmad.getId()))
                .collect(Collectors.toMap(SiteCountView::getDate, SiteCountView::getSiteCount));
        assertThat(sitesForAhmad).containsEntry(LocalDate.of(2026, 9, 19), 2L);
    }

    @Test
    void honoursTheRangeBoundsInclusively() {
        TeamMember m = member("Ahmad");
        MasterWorksite w = worksite("Villa");
        assign(m, w, "2026-08-31");
        assign(m, w, "2026-09-01");
        assign(m, w, "2026-09-30");
        assign(m, w, "2026-10-01");
        em.flush();

        assertThat(workersByDate("2026-09-01", "2026-09-30").keySet())
                .containsExactlyInAnyOrder(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    @Test
    void overtimeOnlyRecordsDoNotAppear() {
        TeamMember assigned = member("Ahmad");
        TeamMember overtimeOnly = member("Sami");
        MasterWorksite w = worksite("Villa");
        assign(assigned, w, "2026-09-19");

        // Overtime lives in its own table and must not create a worker-day.
        WorkAssignmentOvertime ot = new WorkAssignmentOvertime();
        ot.setTeamMember(overtimeOnly);
        ot.setDate(LocalDate.of(2026, 9, 19));
        ot.setOvertimeHours(3.0);
        em.persist(ot);
        em.flush();

        assertThat(workersByDate("2026-09-01", "2026-09-30"))
                .containsExactly(Map.entry(LocalDate.of(2026, 9, 19), 1L));
    }

    @Test
    void earliestAssignmentDateIsTheHistoryFloor() {
        TeamMember m = member("Ahmad");
        MasterWorksite w = worksite("Villa");
        assign(m, w, "2026-09-19");
        assign(m, w, "2025-09-06");
        assign(m, w, "2026-01-02");
        em.flush();

        assertThat(repository.findEarliestAssignmentDate()).isEqualTo(LocalDate.of(2025, 9, 6));
    }

    @Test
    void earliestAssignmentDateIsNullWhenNothingIsRecorded() {
        assertThat(repository.findEarliestAssignmentDate()).isNull();
    }

    @Test
    void emptyRangeReturnsNoRows() {
        TeamMember m = member("Ahmad");
        MasterWorksite w = worksite("Villa");
        assign(m, w, "2026-09-19");
        em.flush();

        assertThat(repository.siteCountsByRange(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).isEmpty();
    }
}
