package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.dto.daily_assignments.AssignmentHistoryDTO;
import life.work.IntFit.backend.mapper.WorkAssignmentMapper;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.repository.TeamMemberRepository;
import life.work.IntFit.backend.repository.WorkAssignmentOvertimeRepository;
import life.work.IntFit.backend.repository.WorkAssignmentRepository;
import life.work.IntFit.backend.repository.projection.SiteCountView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkAssignmentServiceImpl#getHistory}.
 *
 * siteCountsByRange is a repository contract that groups by (date, member), so
 * these tests feed it one row per worker per date — which is what the real
 * query emits — and assert the service turns that into distinct-worker counts.
 */
@ExtendWith(MockitoExtension.class)
class AssignmentHistoryServiceTest {

    @Mock
    private WorkAssignmentRepository workAssignmentRepo;
    @Mock
    private MasterWorksiteRepository masterWorksiteRepo;
    @Mock
    private TeamMemberRepository teamMemberRepo;
    @Mock
    private WorkAssignmentOvertimeRepository overtimeRepo;
    @Mock
    private WorkAssignmentMapper mapper;

    private WorkAssignmentServiceImpl service() {
        return new WorkAssignmentServiceImpl(
                workAssignmentRepo, masterWorksiteRepo, teamMemberRepo, overtimeRepo, mapper, org.mockito.Mockito.mock(life.work.IntFit.backend.service.AssignmentConcurrency.class));
    }

    /** One (date, member) row with a siteCount above 1 still counts as one worker. */
    private static SiteCountView row(String date, long memberId, long siteCount) {
        return new SiteCountView() {
            @Override
            public LocalDate getDate() {
                return LocalDate.parse(date);
            }

            @Override
            public Long getTeamMemberId() {
                return memberId;
            }

            @Override
            public Long getSiteCount() {
                return siteCount;
            }
        };
    }

    @Test
    void countsEachWorkerOnceEvenAcrossSeveralWorksites() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        when(workAssignmentRepo.siteCountsByRange(from, to)).thenReturn(List.of(
                row("2026-09-19", 1L, 3),   // one worker, three worksites
                row("2026-09-19", 2L, 1),
                row("2026-09-20", 1L, 2)
        ));
        when(workAssignmentRepo.findEarliestAssignmentDate()).thenReturn(LocalDate.of(2025, 9, 6));

        AssignmentHistoryDTO result = service().getHistory(from, to);

        assertThat(result.days()).containsExactly(
                new AssignmentHistoryDTO.DayCount(LocalDate.of(2026, 9, 19), 2),
                new AssignmentHistoryDTO.DayCount(LocalDate.of(2026, 9, 20), 1));
        assertThat(result.from()).isEqualTo(from);
        assertThat(result.to()).isEqualTo(to);
        assertThat(result.earliest()).isEqualTo(LocalDate.of(2025, 9, 6));
    }

    @Test
    void returnsDaysAscendingByDateRegardlessOfQueryOrder() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);
        when(workAssignmentRepo.siteCountsByRange(from, to)).thenReturn(List.of(
                row("2026-09-24", 1L, 1),
                row("2026-09-07", 2L, 1),
                row("2026-09-19", 3L, 1)
        ));
        when(workAssignmentRepo.findEarliestAssignmentDate()).thenReturn(LocalDate.of(2025, 9, 6));

        AssignmentHistoryDTO result = service().getHistory(from, to);

        assertThat(result.days()).extracting(AssignmentHistoryDTO.DayCount::date)
                .containsExactly(
                        LocalDate.of(2026, 9, 7),
                        LocalDate.of(2026, 9, 19),
                        LocalDate.of(2026, 9, 24));
    }

    /** A range with no assignments is sparse-empty, not an error. */
    @Test
    void emptyRangeReturnsNoDaysButKeepsTheFloor() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        when(workAssignmentRepo.siteCountsByRange(from, to)).thenReturn(List.of());
        when(workAssignmentRepo.findEarliestAssignmentDate()).thenReturn(LocalDate.of(2025, 9, 6));

        AssignmentHistoryDTO result = service().getHistory(from, to);

        assertThat(result.days()).isEmpty();
        assertThat(result.earliest()).isEqualTo(LocalDate.of(2025, 9, 6));
    }

    /** A database with no assignments at all reports a null floor rather than failing. */
    @Test
    void noAssignmentsAnywhereReportsNullFloor() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        when(workAssignmentRepo.siteCountsByRange(from, to)).thenReturn(List.of());
        when(workAssignmentRepo.findEarliestAssignmentDate()).thenReturn(null);

        AssignmentHistoryDTO result = service().getHistory(from, to);

        assertThat(result.days()).isEmpty();
        assertThat(result.earliest()).isNull();
    }
}
