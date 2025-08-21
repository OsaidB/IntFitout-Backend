package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.*;
import life.work.IntFit.backend.dto.weekly_payroll.*;
import life.work.IntFit.backend.model.entity.*;
import org.mapstruct.Mapper;
import java.util.List;

@Mapper(componentModel = "spring")
public interface PayrollMapper {
    PayrollWeekDTO toDTO(PayrollWeek e);
    PayrollLineDTO toDTO(PayrollLine e);
    PayrollAdjustmentDTO toDTO(PayrollAdjustment e);

    List<PayrollLineDTO> toLineDTOs(List<PayrollLine> e);
    List<PayrollAdjustmentDTO> toAdjDTOs(List<PayrollAdjustment> e);
}
