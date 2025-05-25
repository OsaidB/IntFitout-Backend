package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.MeasurementTaskDTO;
import life.work.IntFit.backend.model.entity.MeasurementTask;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MeasurementTaskMapper {

    @Mapping(target = "measurement", ignore = true)
    @Mapping(source = "room.id", target = "roomId") // ✅ include this line
    MeasurementTaskDTO toDTO(MeasurementTask task);


    @Mapping(target = "room", ignore = true) // ✅ ignore to prevent confusion
    MeasurementTask toEntity(MeasurementTaskDTO dto);

    List<MeasurementTaskDTO> toDTOs(List<MeasurementTask> tasks);

    List<MeasurementTask> toEntities(List<MeasurementTaskDTO> taskDTOs);

    @AfterMapping
    default void setCalculatedFields(MeasurementTask task, @MappingTarget MeasurementTaskDTO dto) {
        if (task.getCalculationType() != null) {
            double m;
            switch (task.getCalculationType()) {
                case FLAT -> m = task.getLength() * task.getWidth();
                case VOLUME -> m = task.getLength() * task.getWidth() * task.getHeight();
                default -> m = 0;
            }
            dto.setMeasurement(m);
        }
    }
}
