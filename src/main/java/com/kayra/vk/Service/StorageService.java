package com.kayra.vk.Service;

import com.kayra.vk.Model.StorageConfig;
import com.kayra.vk.Repository.RecordListRepository;
import com.kayra.vk.Repository.StorageConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final StorageConfigRepository configRepo;
    private final RecordListRepository recordRepo;

    @Value("${storage.local.path:./recordings}")
    private String defaultLocalPath;

    @Value("${storage.local.max-percent:80}")
    private int defaultMaxPercent;

    public StorageService(StorageConfigRepository configRepo, RecordListRepository recordRepo) {
        this.configRepo = configRepo;
        this.recordRepo = recordRepo;
    }

    /**
     * Save file to local storage.
     */
    public Path saveLocally(byte[] data, String fileName) throws IOException {
        Path basePath = getLocalPath();
        Files.createDirectories(basePath);
        Path filePath = basePath.resolve(fileName);
        Files.write(filePath, data);
        log.info("Saved locally: {}", filePath);
        return filePath;
    }

    /**
     * Delete a local file.
     */
    public boolean deleteLocally(String filePath) {
        try {
            return Files.deleteIfExists(Path.of(filePath));
        } catch (IOException e) {
            log.warn("Failed to delete local file: {}", filePath, e);
            return false;
        }
    }

    /**
     * Get the configured local storage path.
     */
    public Path getLocalPath() {
        return configRepo.findByStorageType("LOCAL")
                .filter(StorageConfig::getEnabled)
                .map(c -> Path.of(c.getLocalPath()))
                .orElse(Path.of(defaultLocalPath));
    }

    /**
     * Check disk usage percentage and clean old files if over limit.
     */
    public void enforceDiskLimit() {
        Path localPath = getLocalPath();
        if (!Files.exists(localPath)) return;

        int maxPercent = configRepo.findByStorageType("LOCAL")
                .map(StorageConfig::getLocalMaxPercent)
                .orElse(defaultMaxPercent);

        try {
            FileStore store = Files.getFileStore(localPath);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            int usedPercent = (int) (100 - (usable * 100 / total));

            log.debug("Disk usage: {}% (limit: {}%)", usedPercent, maxPercent);

            if (usedPercent > maxPercent) {
                cleanOldestLocalFiles(localPath);
            }
        } catch (IOException e) {
            log.warn("Failed to check disk usage", e);
        }
    }

    private void cleanOldestLocalFiles(Path localPath) {
        try {
            // Delete oldest recording files first
            var recordings = recordRepo.findAllByOrderByCreatedAtDesc();
            int toDelete = Math.max(1, recordings.size() / 10); // delete ~10% oldest
            var oldest = recordings.subList(
                    Math.max(0, recordings.size() - toDelete), recordings.size());

            for (var rec : oldest) {
                if (rec.getFilePath() != null) {
                    deleteLocally(rec.getFilePath());
                }
                if (rec.getS3Uploaded()) {
                    // File is already in S3, safe to delete local copy
                    recordRepo.delete(rec);
                }
            }
            log.info("Cleaned {} oldest local recording files", oldest.size());
        } catch (Exception e) {
            log.warn("Failed to clean oldest files", e);
        }
    }

    public boolean isLocalEnabled() {
        return configRepo.findByStorageType("LOCAL")
                .map(StorageConfig::getEnabled)
                .orElse(true); // local storage always defaults to enabled
    }
}
