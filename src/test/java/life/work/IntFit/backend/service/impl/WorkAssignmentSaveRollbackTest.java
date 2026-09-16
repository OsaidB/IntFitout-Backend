package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.dto.daily_assignments.CategoryAllocationDTO;
import life.work.IntFit.backend.dto.daily_assignments.DailyWorkAssignmentDTO;
import life.work.IntFit.backend.dto.daily_assignments.MemberAllocationDTO;
import life.work.IntFit.backend.dto.daily_assignments.OvertimeDTO;
import life.work.IntFit.backend.dto.daily_assignments.SimpleAssignmentDTO;
import life.work.IntFit.backend.dto.daily_assignments.SiteHoursDTO;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.model.entity.TeamMember;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.repository.TeamMemberRepository;
import life.work.IntFit.backend.service.WorkAssignmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A daily save writes assignments, allocation values and overtime in one
 * transaction. These tests fail the database mid-save and check that nothing
 * from the attempt survives.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkAssignmentSaveRollbackTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 11);

    @Autowired private WorkAssignmentService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TeamMemberRepository members;
    @Autowired private MasterWorksiteRepository worksites;

    private long m1;
    private long m2;
    private long poison;
    private long siteA;
    private long siteB;
    private long siteC;

    @BeforeEach
    void setUp() {
        m1 = member("Custom Worker");
        m2 = member("Auto Worker");
        poison = member("Poison Worker");
        siteA = worksite("Rollback A");
        siteB = worksite("Rollback B");
        siteC = worksite("Rollback C");

        // committed starting point: m1 custom on A/B with 2h overtime, m2 automatic on A with 1h
        service.saveAssignmentsForDate(day(
                List.of(a(m1, siteA), a(m1, siteB), a(m2, siteA)),
                List.of(ot(m1, "2"), ot(m2, "1")),
                List.of(new MemberAllocationDTO(m1,
                        custom(siteA, "6", siteB, "2"),
                        custom(siteA, "0", siteB, "2")))));
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS TEST_FAIL_OVERTIME");
        FailingOvertimeTrigger.poisonMemberId = -1;
        jdbc.update("delete from work_assignment_overtime");
        jdbc.update("delete from work_assignments");
        jdbc.update("delete from team_members");
        jdbc.update("delete from master_worksites");
    }

    @Test
    void failureAfterAssignmentAllocationAndOvertimeWritesRollsBackEverything() {
        List<String> assignmentsBefore = assignments();
        List<String> overtimeBefore = overtime();

        boolean h2 = isH2();
        if (h2) {
            FailingOvertimeTrigger.poisonMemberId = poison;
            FailingOvertimeTrigger.observedAssignments = List.of();
            FailingOvertimeTrigger.observedOvertime = List.of();
            jdbc.execute("CREATE TRIGGER TEST_FAIL_OVERTIME BEFORE INSERT ON WORK_ASSIGNMENT_OVERTIME "
                    + "FOR EACH ROW CALL '" + FailingOvertimeTrigger.class.getName() + "'");
        } else {
            jdbc.execute("CREATE TRIGGER TEST_FAIL_OVERTIME BEFORE INSERT ON work_assignment_overtime "
                    + "FOR EACH ROW BEGIN IF NEW.team_member_id = " + poison + " THEN "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced failure after writes began'; "
                    + "END IF; END");
        }

        // m1 moves from B to C with new custom values and more overtime, m2 leaves the day,
        // the poison worker's raw overtime is written last and fails
        DailyWorkAssignmentDTO change = day(
                List.of(a(m1, siteA), a(m1, siteC)),
                List.of(ot(m1, "3"), ot(poison, "4")),
                List.of(new MemberAllocationDTO(m1,
                        custom(siteA, "5", siteC, "3"),
                        custom(siteA, "1", siteC, "2"))));

        assertThatThrownBy(() -> service.saveAssignmentsForDate(change))
                .hasStackTraceContaining("forced failure after writes began");

        if (h2) {
            // inside the failed transaction every kind of write had already happened
            assertThat(FailingOvertimeTrigger.observedAssignments).containsExactly(
                    m1 + "|" + siteA + "|5|1",
                    m1 + "|" + siteC + "|3|2");
            assertThat(FailingOvertimeTrigger.observedOvertime).containsExactly(m1 + "|3.0");
        }

        // and none of it was committed
        assertThat(assignments()).isEqualTo(assignmentsBefore);
        assertThat(overtime()).isEqualTo(overtimeBefore);
        assertThat(assignmentsBefore).containsExactly(
                m1 + "|" + siteA + "|6|0",
                m1 + "|" + siteB + "|2|2",
                m2 + "|" + siteA + "|null|null");
        assertThat(overtimeBefore).containsExactly(m1 + "|2.0", m2 + "|1.0");
    }

    @Test
    void constraintViolationDuringOvertimeWriteRollsBackEverything() {
        List<String> assignmentsBefore = assignments();
        List<String> overtimeBefore = overtime();
        long missingMember = poison + 10_000;

        DailyWorkAssignmentDTO change = day(
                List.of(a(m1, siteA), a(m1, siteB)),
                List.of(ot(m1, "2"), ot(missingMember, "1")),
                List.of(new MemberAllocationDTO(m1, auto(), auto())));

        assertThatThrownBy(() -> service.saveAssignmentsForDate(change))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(assignments()).isEqualTo(assignmentsBefore);
        assertThat(overtime()).isEqualTo(overtimeBefore);
    }

    // ---------- helpers ----------

    private List<String> assignments() {
        return jdbc.query("select team_member_id, master_worksite_id, regular_hours_override, "
                        + "overtime_hours_override from work_assignments where date = ? "
                        + "order by team_member_id, master_worksite_id",
                (rs, i) -> rs.getLong(1) + "|" + rs.getLong(2) + "|"
                        + plain(rs.getBigDecimal(3)) + "|" + plain(rs.getBigDecimal(4)),
                DAY);
    }

    private List<String> overtime() {
        return jdbc.query("select team_member_id, overtime_hours from work_assignment_overtime "
                        + "where date = ? order by team_member_id",
                (rs, i) -> rs.getLong(1) + "|" + rs.getDouble(2),
                DAY);
    }

    private boolean isH2() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
                c.getMetaData().getDatabaseProductName().toLowerCase().contains("h2")));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    private static DailyWorkAssignmentDTO day(List<SimpleAssignmentDTO> assignments,
                                              List<OvertimeDTO> overtime,
                                              List<MemberAllocationDTO> allocations) {
        DailyWorkAssignmentDTO dto = new DailyWorkAssignmentDTO();
        dto.setDate(DAY);
        dto.setAssignments(assignments);
        dto.setOvertime(overtime);
        dto.setAllocations(allocations);
        return dto;
    }

    private static SimpleAssignmentDTO a(long memberId, long siteId) {
        SimpleAssignmentDTO dto = new SimpleAssignmentDTO();
        dto.setTeamMemberId(memberId);
        dto.setMasterWorksiteId(siteId);
        return dto;
    }

    private static OvertimeDTO ot(long memberId, String hours) {
        return new OvertimeDTO(memberId, new BigDecimal(hours));
    }

    private static CategoryAllocationDTO auto() {
        return new CategoryAllocationDTO("AUTO", null);
    }

    private static CategoryAllocationDTO custom(long siteOne, String hoursOne, long siteTwo, String hoursTwo) {
        return new CategoryAllocationDTO("CUSTOM", List.of(
                new SiteHoursDTO(siteOne, new BigDecimal(hoursOne)),
                new SiteHoursDTO(siteTwo, new BigDecimal(hoursTwo))));
    }

    private long member(String name) {
        TeamMember tm = new TeamMember();
        tm.setName(name);
        tm.setRole("Painter");
        tm.setDailyWage(200.0);
        return members.save(tm).getId();
    }

    private long worksite(String name) {
        return worksites.save(MasterWorksite.builder().approvedName(name).build()).getId();
    }
}
