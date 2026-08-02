package com.kayra.vk.Controller;

import com.kayra.vk.Model.RecordList;
import com.kayra.vk.Repository.RecordListRepository;
import com.kayra.vk.Service.RecordingService;
import com.kayra.vk.Service.S3Service;
import com.kayra.vk.Service.VideoService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class RecordController {

    private final VideoService videoService;
    private final RecordingService recordingService;
    private final RecordListRepository recordRepo;
    private final S3Service s3Service;

    // In-memory sync status tracking (persisted in DB for power outage resilience)
    private final Map<Long, String> syncStatus = new ConcurrentHashMap<>();

    public RecordController(VideoService videoService, RecordingService recordingService,
                            RecordListRepository recordRepo, S3Service s3Service) {
        this.videoService = videoService;
        this.recordingService = recordingService;
        this.recordRepo = recordRepo;
        this.s3Service = s3Service;
    }

    @GetMapping("/record")
    public String recordPage(Model model) {
        model.addAttribute("pageTitle", "Record");
        model.addAttribute("isRecording", recordingService.isRecording());
        return "record";
    }

    @PostMapping("/api/record/start")
    @ResponseBody
    public ResponseEntity<?> start(@RequestParam("orderNo") String orderNo) {
        if (recordingService.isRecording()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Already recording. Stop current recording first."
            ));
        }
        try {
            String safeOrderNo = orderNo.replaceAll("[^a-zA-Z0-9\\-]", "_");
            String timestamp = String.valueOf(System.currentTimeMillis());
            String fileName = safeOrderNo + "_" + timestamp + ".mp4";
            Path outputPath = Path.of("./recordings", fileName);
            Files.createDirectories(outputPath.getParent());
            recordingService.startRecording(orderNo, outputPath.toAbsolutePath().toString());
            return ResponseEntity.ok(Map.of("success", true, "message", "Recording started"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/api/record/stop")
    @ResponseBody
    public ResponseEntity<?> stop(Authentication auth) {
        if (!recordingService.isRecording()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Not recording"
            ));
        }
        try {
            // Get orderNo and elapsed BEFORE stopping (stopRecording clears them)
            RecordingService.RecordingStatus status = recordingService.getStatus();
            String orderNo = status.orderNo();
            int elapsedSec = (int) status.elapsedSec();

            String filePath = recordingService.stopRecording();
            File videoFile = new File(filePath);
            if (!videoFile.exists()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Recording file not found"
                ));
            }

            // Save via VideoService
            byte[] videoBytes = Files.readAllBytes(videoFile.toPath());
            String fileName = videoFile.getName();

            RecordList record = videoService.saveRecordingFromFile(
                    videoBytes, fileName, orderNo, elapsedSec, auth.getName());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "id", record.getId(),
                    "fileName", record.getFileName(),
                    "fileSize", record.getFileSize(),
                    "s3Uploaded", record.getS3Uploaded()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/api/record/status")
    @ResponseBody
    public RecordingService.RecordingStatus status() {
        return recordingService.getStatus();
    }

    @PostMapping("/api/record/upload")
    @ResponseBody
    public ResponseEntity<?> upload(@RequestParam("video") MultipartFile video,
                                     @RequestParam("orderNo") String orderNo,
                                     @RequestParam(value = "durationSec", required = false) Integer durationSec,
                                     Authentication auth) {
        try {
            RecordList record = videoService.saveRecording(
                    video, orderNo, durationSec, auth.getName());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "id", record.getId(),
                    "fileName", record.getFileName(),
                    "s3Uploaded", record.getS3Uploaded()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/api/record/photo")
    @ResponseBody
    public ResponseEntity<?> takePhoto(@RequestParam("orderNo") String orderNo,
                                        Authentication auth) {
        try {
            String safeOrderNo = orderNo.replaceAll("[^a-zA-Z0-9\\-]", "_");
            String timestamp = String.valueOf(System.currentTimeMillis());
            String fileName = safeOrderNo + "_photo_" + timestamp + ".jpg";
            Path outputPath = Path.of("./recordings", fileName);

            // Capture frame from server camera
            String photoPath = recordingService.capturePhoto(orderNo, outputPath.toAbsolutePath().toString());
            byte[] photoBytes = Files.readAllBytes(Path.of(photoPath));

            // Save via VideoService
            RecordList record = videoService.savePhotoFromFile(
                    photoBytes, fileName, orderNo, auth.getName());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "id", record.getId(),
                    "fileName", record.getFileName(),
                    "fileSize", record.getFileSize()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/api/cameras")
    @ResponseBody
    public List<Map<String, String>> listCameras() {
        return recordingService.listCameras();
    }

    @PostMapping("/api/cameras")
    @ResponseBody
    public ResponseEntity<?> selectCamera(@RequestParam("device") String device) {
        recordingService.setCameraDevice(device);
        return ResponseEntity.ok(Map.of("success", true, "device", device));
    }

    @GetMapping("/api/record/file/{id}")
    @ResponseBody
    public ResponseEntity<?> serveFile(@PathVariable Long id) {
        var record = recordRepo.findById(id);
        if (record.isEmpty()) return ResponseEntity.notFound().build();
        try {
            Path filePath = Path.of(record.get().getFilePath()).normalize();
            Path recordingsDir = Path.of("./recordings").toAbsolutePath().normalize();
            if (!filePath.startsWith(recordingsDir)) {
                return ResponseEntity.status(403).build();
            }
            byte[] data = Files.readAllBytes(filePath);
            String contentType = record.get().getFileName().endsWith(".jpg") ? "image/jpeg" : "video/mp4";
            return ResponseEntity.ok()
                    .header("Content-Type", contentType)
                    .header("Content-Disposition", "inline; filename=" + record.get().getFileName())
                    .body(data);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/api/record/list")
    @ResponseBody
    public List<RecordList> list() {
        return recordRepo.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping("/api/record/{id}/share")
    @ResponseBody
    public ResponseEntity<?> share(@PathVariable Long id) {
        var record = recordRepo.findById(id);
        if (record.isEmpty()) return ResponseEntity.notFound().build();
        if (!record.get().getS3Uploaded()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "File not uploaded to S3. Sync it first."
            ));
        }
        if (!s3Service.isS3Enabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "S3 is not configured"
            ));
        }
        // Use public CDN URL if configured, otherwise generate presigned URL
        String publicUrl = s3Service.getPublicUrl();
        if (publicUrl != null && !publicUrl.isBlank()) {
            String url = publicUrl.endsWith("/") ? publicUrl + record.get().getFileName()
                    : publicUrl + "/" + record.get().getFileName();
            return ResponseEntity.ok(Map.of("success", true, "url", url));
        }
        try {
            String url = s3Service.generatePresignedUrl(
                    record.get().getFileName(), Duration.ofDays(7));
            return ResponseEntity.ok(Map.of("success", true, "url", url));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/api/record/{id}/sync-to-s3")
    @ResponseBody
    public ResponseEntity<?> syncToS3(@PathVariable Long id) {
        var record = recordRepo.findById(id);
        if (record.isEmpty()) return ResponseEntity.notFound().build();
        if (record.get().getS3Uploaded()) {
            return ResponseEntity.ok(Map.of("success", true, "status", "already-synced"));
        }
        if (!s3Service.isS3Enabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "S3 is not configured"));
        }
        if ("syncing".equals(syncStatus.get(id))) {
            return ResponseEntity.ok(Map.of("success", true, "status", "syncing"));
        }

        // Start async sync
        syncStatus.put(id, "syncing");
        new Thread(() -> {
            try {
                boolean ok = s3Service.uploadFromLocal(record.get());
                syncStatus.put(id, ok ? "done" : "error");
            } catch (Exception e) {
                syncStatus.put(id, "error");
            }
        }, "s3-sync-" + id).start();

        return ResponseEntity.ok(Map.of("success", true, "status", "syncing"));
    }

    @GetMapping("/api/record/{id}/sync-status")
    @ResponseBody
    public ResponseEntity<?> syncStatus(@PathVariable Long id) {
        var record = recordRepo.findById(id);
        if (record.isEmpty()) return ResponseEntity.notFound().build();
        String ramStatus = syncStatus.getOrDefault(id,
                record.get().getS3Uploaded() ? "done" : "not-synced");
        return ResponseEntity.ok(Map.of("status", ramStatus));
    }

    @DeleteMapping("/api/record/{id}")
    @ResponseBody
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            videoService.deleteRecording(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }
}
