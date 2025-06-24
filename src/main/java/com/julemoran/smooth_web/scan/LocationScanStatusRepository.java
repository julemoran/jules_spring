package com.julemoran.smooth_web.scan;

import com.julemoran.smooth_web.location.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface LocationScanStatusRepository extends JpaRepository<LocationScanStatus, Long> {
    Optional<LocationScanStatus> findByLocation(Location location);
    Optional<LocationScanStatus> findByLocationId(Long locationId);

    @Transactional
    void deleteByLocation_Id(Long locationId); // Method to delete by location ID
}
