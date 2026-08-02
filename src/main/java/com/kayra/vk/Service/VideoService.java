package com.kayra.vk.Service;

import com.kayra.vk.Model.RecordList;
import com.kayra.vk.Repository.RecordListRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Service
public class VideoService {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);

    private final StorageService storageService;
    private final S3Service s3Service;
    private final RecordListRepository recordRepo;

    public VideoService(StorageService storageService, S3Service s3Service,
                        RecordListRepository recordRepo) {
        this.storageService = storageService;
        this.s3Service = s3Service;
        this.recordRepo = recordRepo;
    }

    /**
     * Process a recorded video: save locally, optionally upload to S3, create DB record.
     */
    @Transactional
    public RecordList saveRecording(MultipartFile video, String orderNo,
                                    Integer durationSec, String recordedBy) throws IOException {
        // Generate unique filename
        String timestamp = String.valueOf(System.currentTimeMillis());
        String fileName = orderNo.replaceAll("[^a-zA-Z0-9\\-]", "_") + "_" + timestamp + ".webm";

        // 1. Save locally
        byte[] videoBytes = video.getBytes();
        Path localPath = storageService.saveLocally(videoBytes, fileName);

        // 2. Create DB record
        RecordList record = RecordList.builder()
                .orderNo(orderNo)
                .fileName(fileName)
                .filePath(localPath.toAbsolutePath().toString())
                .fileSize((long) videoBytes.length)
                .durationSec(durationSec)
                .recordedBy(recordedBy)
                .s3Uploaded(false)
                .createdAt(LocalDateTime.now())
                .build();
        record = recordRepo.save(record);

        // 3. Upload to S3 if enabled
        if (s3Service.isS3Enabled()) {
            String s3Url = s3Service.upload(localPath, fileName, "video/webm");
            if (s3Url != null) {
                record.setS3Url(s3Url);
                record.setS3Uploaded(true);
                recordRepo.save(record);
            }
        }

        // 4. Enforce storage limits
        storageService.enforceDiskLimit();
        s3Service.enforceRetentionPolicy();

        log.info("Recording saved: {} (order: {}, size: {} bytes)", fileName, orderNo, videoBytes.length);
        return record;
    }

    /**
     * Save a recording from the server-side camera (already written to disk by RecordingService).
     */
    @Transactional
    public RecordList saveRecordingFromFile(byte[] videoBytes, String fileName, String orderNo,
                                             Integer durationSec, String recordedBy) throws IOException {
        Path localPath = storageService.saveLocally(videoBytes, fileName);

        RecordList record = RecordList.builder()
                .orderNo(orderNo)
                .fileName(fileName)
                .filePath(localPath.toAbsolutePath().toString())
                .fileSize((long) videoBytes.length)
                .durationSec(durationSec)
                .recordedBy(recordedBy)
                .s3Uploaded(false)
                .createdAt(LocalDateTime.now())
                .build();
        record = recordRepo.save(record);

        if (s3Service.isS3Enabled()) {
            String s3Url = s3Service.upload(localPath, fileName, "video/mp4");
            if (s3Url != null) {
                record.setS3Url(s3Url);
                record.setS3Uploaded(true);
                recordRepo.save(record);
            }
        }

        storageService.enforceDiskLimit();
        s3Service.enforceRetentionPolicy();

        log.info("Recording saved: {} (order: {}, size: {} bytes)", fileName, orderNo, videoBytes.length);
        return record;
    }

    /**
     * Save a photo from raw bytes (captured from server camera).
     */
    @Transactional
    public RecordList savePhotoFromFile(byte[] photoBytes, String fileName, String orderNo,
                                         String recordedBy) throws IOException {
        Path localPath = storageService.saveLocally(photoBytes, fileName);

        RecordList record = RecordList.builder()
                .orderNo(orderNo)
                .fileName(fileName)
                .filePath(localPath.toAbsolutePath().toString())
                .fileSize((long) photoBytes.length)
                .durationSec(0)
                .recordedBy(recordedBy)
                .s3Uploaded(false)
                .createdAt(LocalDateTime.now())
                .build();
        record = recordRepo.save(record);

        if (s3Service.isS3Enabled()) {
            String s3Url = s3Service.upload(localPath, fileName, "image/jpeg");
            if (s3Url != null) {
                record.setS3Url(s3Url);
                record.setS3Uploaded(true);
                recordRepo.save(record);
            }
        }

        storageService.enforceDiskLimit();
        log.info("Photo saved: {} (order: {})", fileName, orderNo);
        return record;
    }

    /**
     * Save a still photo captured from the camera (multipart upload).
     */
    @Transactional
    public String savePhoto(MultipartFile photo, String orderNo, String recordedBy) throws IOException {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String fileName = orderNo.replaceAll("[^a-zA-Z0-9\\-]", "_") + "_photo_" + timestamp + ".jpg";
        savePhotoFromFile(photo.getBytes(), fileName, orderNo, recordedBy);
        return fileName;
    }

    /**
     * Delete a recording (local file, S3 file, and DB record).
     */
    @Transactional
    public void deleteRecording(Long id) {
        recordRepo.findById(id).ifPresent(record -> {
            // Delete local file
            if (record.getFilePath() != null) {
                storageService.deleteLocally(record.getFilePath());
            }
            // Delete S3 file
            if (record.getS3Uploaded() && record.getFileName() != null) {
                s3Service.delete(record.getFileName());
            }
            // Delete DB record
            recordRepo.delete(record);
            log.info("Deleted recording: {}", record.getFileName());
        });
    }
}
