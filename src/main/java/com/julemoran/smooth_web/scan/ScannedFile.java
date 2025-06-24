package com.julemoran.smooth_web.scan;

import com.julemoran.smooth_web.location.Location;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "scanned_files")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScannedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Column(nullable = false)
    private String relativePath;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private long fileSize; // in bytes

    @Column(nullable = false)
    private LocalDateTime creationDate;

    @Column(nullable = false)
    private LocalDateTime lastModifiedDate;

    @Column(length = 64) // SHA256 hash is 64 characters long
    private String sha256Hash;
}
