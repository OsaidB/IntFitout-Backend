package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.Area;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AreaRepository extends JpaRepository<Area, Long> {

    List<Area> findByCityNameIgnoreCaseOrderByNameAsc(String cityName);

    Optional<Area> findByCityNameIgnoreCaseAndNameIgnoreCase(String cityName, String areaName);

    boolean existsByCityNameIgnoreCaseAndNameIgnoreCase(String cityName, String areaName);
}