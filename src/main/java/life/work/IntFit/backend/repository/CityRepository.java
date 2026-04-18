package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.City;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CityRepository extends JpaRepository<City, Long> {

    boolean existsByNameIgnoreCase(String name);

    Optional<City> findByNameIgnoreCase(String name);

    List<City> findAllByOrderByNameAsc();
}