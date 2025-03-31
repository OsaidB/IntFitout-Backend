package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.MeasurementTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MeasurementTaskRepository extends JpaRepository<MeasurementTask, Long> {
    List<MeasurementTask> findByRoomId(Long roomId);
}
