package com.kayra.vk.Service;

import com.kayra.vk.Model.RecordList;
import com.kayra.vk.Model.StorageConfig;
import com.kayra.vk.Repository.RecordListRepository;
import com.kayra.vk.Repository.StorageConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class S3Service {

    private static final Logger log = LoggerFactory.getLogger(S3Service.class);

    private final StorageConfigRepository configRepo;
    private final RecordListRepository recordRepo;

    public S3Service(StorageConfigRepository configRepo, RecordListRepository recordRepo) {
        this.configRepo = configRepo;
        this.recordRepo = recordRepo;
    }

    /**
     * Upload a file to Bunny S3 (or any S3-compatible storage).
     */
    public String upload(Path filePath, String fileName, String contentType) {
        Optional<StorageConfig> cfgOpt = getS3Config();
        if (cfgOpt.isEmpty() || !cfgOpt.get().getEnabled()) {
            log.debug("S3 not configured, skipping upload for: {}", fileName);
            return null;
        }

        StorageConfig cfg = cfgOpt.get();
        S3Client client = createClient(cfg);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(cfg.getS3Bucket())
                    .key(fileName)
                    .contentType(contentType)
                    .build();

            client.putObject(request, RequestBody.fromFile(filePath));
            String url = String.format("%s/%s/%s", cfg.getS3Endpoint(), cfg.getS3Bucket(), fileName);
            log.info("Uploaded to S3: {}", url);
            return url;
        } catch (Exception e) {
            log.error("Failed to upload to S3: {}", fileName, e);
            return null;
        } finally {
            client.close();
        }
    }

    /**
     * Delete a file from S3.
     */
    public boolean delete(String fileName) {
        Optional<StorageConfig> cfgOpt = getS3Config();
        if (cfgOpt.isEmpty() || !cfgOpt.get().getEnabled()) return false;

        StorageConfig cfg = cfgOpt.get();
        S3Client client = createClient(cfg);

        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(cfg.getS3Bucket())
                    .key(fileName)
                    .build();
            client.deleteObject(request);
            log.info("Deleted from S3: {}", fileName);
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete from S3: {}", fileName, e);
            return false;
        } finally {
            client.close();
        }
    }

    /**
     * Enforce S3 retention policy: delete files older than retentionDays.
     */
    public void enforceRetentionPolicy() {
        Optional<StorageConfig> cfgOpt = getS3Config();
        if (cfgOpt.isEmpty() || !cfgOpt.get().getEnabled()) return;

        StorageConfig cfg = cfgOpt.get();
        int retentionDays = cfg.getS3RetentionDays() != null ? cfg.getS3RetentionDays() : 30;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        List<RecordList> oldRecords = recordRepo.findByCreatedAtBefore(cutoff);
        S3Client client = createClient(cfg);

        try {
            for (RecordList rec : oldRecords) {
                if (rec.getS3Uploaded() && rec.getFileName() != null) {
                    DeleteObjectRequest request = DeleteObjectRequest.builder()
                            .bucket(cfg.getS3Bucket())
                            .key(rec.getFileName())
                            .build();
                    client.deleteObject(request);
                    log.info("Retention cleanup: deleted {} from S3", rec.getFileName());
                }
                recordRepo.delete(rec);
            }
            if (!oldRecords.isEmpty()) {
                log.info("S3 retention cleanup: removed {} old records", oldRecords.size());
            }
        } finally {
            client.close();
        }
    }

    public boolean isS3Enabled() {
        return getS3Config().map(StorageConfig::getEnabled).orElse(false);
    }

    public String getPublicUrl() {
        return getS3Config().map(StorageConfig::getS3PublicUrl).orElse(null);
    }

    /**
     * Generate a pre-signed URL valid for the specified duration.
     */
    public String generatePresignedUrl(String fileName, java.time.Duration duration) {
        Optional<StorageConfig> cfgOpt = getS3Config();
        if (cfgOpt.isEmpty() || !cfgOpt.get().getEnabled()) return null;
        StorageConfig cfg = cfgOpt.get();
        String endpoint = cfg.getS3Endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            String region = cfg.getS3Region() != null ? cfg.getS3Region() : "de";
            endpoint = "https://" + region + "-s3.storage.bunnycdn.com";
        }
        String region = cfg.getS3Region() != null ? cfg.getS3Region() : "de";
        try {
            var request = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                    .bucket(cfg.getS3Bucket())
                    .key(fileName)
                    .build();
            var presigner = software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
                    .region(Region.of(region))
                    .endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(cfg.getS3AccessKey(), cfg.getS3SecretKey())))
                    .build();
            var presignedRequest = presigner.presignGetObject(r -> r
                    .getObjectRequest(request)
                    .signatureDuration(duration));
            presigner.close();
            return presignedRequest.url().toString();
        } catch (Exception e) {
            log.error("Failed to generate presigned URL", e);
            return null;
        }
    }

    /**
     * Upload a local file to S3 and update the DB record.
     */
    public boolean uploadFromLocal(com.kayra.vk.Model.RecordList record) {
        String url = upload(Path.of(record.getFilePath()), record.getFileName(),
                record.getFileName().endsWith(".jpg") ? "image/jpeg" : "video/mp4");
        if (url != null) {
            record.setS3Url(url);
            record.setS3Uploaded(true);
            recordRepo.save(record);
            return true;
        }
        return false;
    }

    private Optional<StorageConfig> getS3Config() {
        return configRepo.findByStorageType("S3");
    }

    private S3Client createClient(StorageConfig cfg) {
        String endpoint = cfg.getS3Endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            String region = cfg.getS3Region() != null ? cfg.getS3Region() : "de";
            endpoint = "https://" + region + "-s3.storage.bunnycdn.com";
        }
        String region = cfg.getS3Region() != null ? cfg.getS3Region() : "de";
        return S3Client.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.getS3AccessKey(), cfg.getS3SecretKey())))
                .forcePathStyle(true)
                .build();
    }
}
