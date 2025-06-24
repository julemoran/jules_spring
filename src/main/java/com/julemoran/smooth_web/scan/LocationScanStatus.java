package com.julemoran.smooth_web.scan;

import com.julemoran.smooth_web.location.Location;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "location_scan_status")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationScanStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false, unique = true)
    private Location location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanStatus status;

    private LocalDateTime lastScanStartTime;

    private LocalDateTime lastScanEndTime;

    public LocationScanStatus(Location location, ScanStatus status) {
        this.location = location;
        this.status = status;
    }
}
