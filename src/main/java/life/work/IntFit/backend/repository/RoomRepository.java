package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long> {
    List<Room> findByWorksiteId(Long worksiteId);

    @Query("SELECT r FROM Room r LEFT JOIN FETCH r.tasks WHERE r.worksite.id = :worksiteId")
    List<Room> findByWorksiteIdWithTasks(@Param("worksiteId") Long worksiteId);

    @Query("SELECT r FROM Room r LEFT JOIN FETCH r.tasks WHERE r.id = :roomId")
    Optional<Room> findByIdWithTasks(@Param("roomId") Long roomId);

}
