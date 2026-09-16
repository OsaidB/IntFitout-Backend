// package: life.work.IntFit.backend.mapper
package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.daily_assignments.WorkAssignmentDTO;
import life.work.IntFit.backend.model.entity.WorkAssignment;
import life.work.IntFit.backend.model.enums.AllocationMode;
import life.work.IntFit.backend.service.allocation.DecimalHours;
import life.work.IntFit.backend.service.allocation.HourAllocation;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class WorkAssignmentMapper {

    private static final double BASE_DAY_HOURS = 8.0;

    /** Legacy/basic mapping (no overtime/allocated fields). */
    public WorkAssignmentDTO toDTO(WorkAssignment assignment) {
        WorkAssignmentDTO dto = new WorkAssignmentDTO();
        dto.setId(assignment.getId());
        dto.setTeamMemberId(assignment.getTeamMember().getId());
        dto.setMasterWorksiteId(assignment.getMasterWorksite().getId());
        dto.setTeamMemberName(assignment.getTeamMember().getName());
        dto.setMasterWorksiteName(assignment.getMasterWorksite().getApprovedName());
        dto.setDate(assignment.getDate());
        dto.setTeamMemberDailyWage(assignment.getTeamMember().getDailyWage());
        dto.setRegularAllocationMode(AllocationMode.of(assignment.getRegularHoursOverride()));
        dto.setOvertimeAllocationMode(AllocationMode.of(assignment.getOvertimeHoursOverride()));
        dto.setRegularHoursOverride(DecimalHours.toPlainString(assignment.getRegularHoursOverride()));
        dto.setOvertimeHoursOverride(DecimalHours.toPlainString(assignment.getOvertimeHoursOverride()));
        // overtimeHours / allocatedHours / resolved hours intentionally left null in this variant
        return dto;
    }

    /**
     * Enriched mapping that includes:
     * - overtimeHours: per-member overtime for that date (may be null)
     * - allocatedHours: resolved hours for this row rounded to 2 decimals (null if siteCountForMember == null/0)
     * - resolved*Hours: the same hours unrounded, per category and combined
     */
    public WorkAssignmentDTO toDTO(WorkAssignment assignment, Double overtimeHours, Integer siteCountForMember) {
        WorkAssignmentDTO dto = toDTO(assignment);

        // Keep null if not provided (caller may use null to indicate "not loaded")
        dto.setOvertimeHours(overtimeHours);
        dto.setOvertimeHoursDecimal(overtimeHours == null || !Double.isFinite(overtimeHours)
                ? null
                : DecimalHours.toPlainString(DecimalHours.canonical(overtimeHours)));
        dto.setSiteCount(siteCountForMember);

        if (siteCountForMember != null && siteCountForMember > 0) {
            double otForSplit = (overtimeHours != null && overtimeHours > 0d) ? overtimeHours : 0d;
            BigDecimal regularOverride = assignment.getRegularHoursOverride();
            BigDecimal overtimeOverride = assignment.getOvertimeHoursOverride();

            double total;
            if (HourAllocation.isAuto(regularOverride, overtimeOverride)) {
                total = (BASE_DAY_HOURS + otForSplit) / siteCountForMember;
            } else {
                total = HourAllocation.quotient(
                        HourAllocation.effectiveNumerator(regularOverride, overtimeOverride, otForSplit, siteCountForMember),
                        siteCountForMember);
            }
            dto.setResolvedRegularHours(regularOverride != null
                    ? regularOverride.doubleValue()
                    : BASE_DAY_HOURS / siteCountForMember);
            dto.setResolvedOvertimeHours(overtimeOverride != null
                    ? overtimeOverride.doubleValue()
                    : otForSplit / siteCountForMember);
            dto.setResolvedTotalHours(total);
            dto.setAllocatedHours(round2(total));
        } else {
            dto.setAllocatedHours(null);
        }

        return dto;
    }

    /** Backward-compat overload: accepts Integer OT and delegates to Double version. */
    public WorkAssignmentDTO toDTO(WorkAssignment assignment, Integer overtimeHours, Integer siteCountForMember) {
        Double ot = (overtimeHours == null) ? null : overtimeHours.doubleValue();
        return toDTO(assignment, ot, siteCountForMember);
    }

    private Double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
