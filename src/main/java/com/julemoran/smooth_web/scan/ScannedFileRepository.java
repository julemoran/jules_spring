package com.julemoran.smooth_web.scan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ScannedFileRepository extends JpaRepository<ScannedFile, Long> {
    List<ScannedFile> findByLocationId(Long locationId);

    @Transactional
    void deleteByLocationId(Long locationId); // For cleaning up files if a location is deleted or re-scanned
}
