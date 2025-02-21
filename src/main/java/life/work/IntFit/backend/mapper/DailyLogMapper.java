package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.DailyLogDTO;
import life.work.IntFit.backend.dto.WorkSessionDTO;
import life.work.IntFit.backend.model.entity.DailyLog;
import life.work.IntFit.backend.model.entity.WorkSession;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper(componentModel = "spring")
public interface DailyLogMapper {
    DailyLogMapper INSTANCE = Mappers.getMapper(DailyLogMapper.class);

    @Mapping(source = "employee.id", target = "employeeId")
    @Mapping(source = "employee.name", target = "employeeName")
    DailyLogDTO toDTO(DailyLog dailyLog);

    DailyLog toEntity(DailyLogDTO dailyLogDTO);

    List<DailyLogDTO> toDTOList(List<DailyLog> dailyLogs);

    List<DailyLog> toEntityList(List<DailyLogDTO> dailyLogDTOs);

    @Mapping(source = "worksite.id", target = "worksiteId")
    @Mapping(source = "worksite.name", target = "worksiteName")
    WorkSessionDTO toDTO(WorkSession workSession);

    WorkSession toEntity(WorkSessionDTO workSessionDTO);

    List<WorkSessionDTO> toDTOListWorkSession(List<WorkSession> workSessions);

    List<WorkSession> toEntityListWorkSession(List<WorkSessionDTO> workSessionDTOs);
}
