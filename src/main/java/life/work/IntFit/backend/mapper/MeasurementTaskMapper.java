package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.MeasurementTaskDTO;
import life.work.IntFit.backend.model.entity.MeasurementTask;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MeasurementTaskMapper {

    MeasurementTaskDTO toDTO(MeasurementTask task);

    MeasurementTask toEntity(MeasurementTaskDTO dto);

    List<MeasurementTaskDTO> toDTOs(List<MeasurementTask> tasks);

    List<MeasurementTask> toEntities(List<MeasurementTaskDTO> taskDTOs);
}
