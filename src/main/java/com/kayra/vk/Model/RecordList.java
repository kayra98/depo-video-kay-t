package com.kayra.vk.Model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "record_list")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false)
    private String orderNo;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "s3_url", length = 1000)
    private String s3Url;

    @Column(name = "s3_uploaded")
    @Builder.Default
    private Boolean s3Uploaded = false;

    @Column(name = "recorded_by")
    private String recordedBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
