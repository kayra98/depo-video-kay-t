package com.kayra.vk.Model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "storage_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_type", nullable = false, length = 10)
    private String storageType;  // "LOCAL" veya "S3"

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = false;

    // Lokal depolama
    @Column(name = "local_path", length = 500)
    private String localPath;

    @Column(name = "local_max_percent")
    @Builder.Default
    private Integer localMaxPercent = 80;

    // S3 / Bunny
    @Column(name = "s3_endpoint", length = 500)
    private String s3Endpoint;

    @Column(name = "s3_region", length = 100)
    private String s3Region;

    @Column(name = "s3_bucket")
    private String s3Bucket;

    @Column(name = "s3_access_key", length = 500)
    private String s3AccessKey;

    @Column(name = "s3_secret_key", length = 500)
    private String s3SecretKey;

    @Column(name = "s3_public_url", length = 500)
    private String s3PublicUrl;

    @Column(name = "s3_retention_days")
    @Builder.Default
    private Integer s3RetentionDays = 30;

    // Video recording quality (used when storage_type = "RECORDING")
    @Column(name = "video_width")
    @Builder.Default
    private Integer videoWidth = 1280;

    @Column(name = "video_height")
    @Builder.Default
    private Integer videoHeight = 720;

    @Column(name = "video_fps")
    @Builder.Default
    private Integer videoFps = 30;

    @Column(name = "video_bitrate")
    @Builder.Default
    private Integer videoBitrate = 5_000_000;
}
