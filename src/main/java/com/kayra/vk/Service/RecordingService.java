package com.kayra.vk.Service;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kayra.vk.Model.StorageConfig;
import com.kayra.vk.Repository.StorageConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RecordingService {

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);

    @Value("${camera.device:0}")
    private String cameraDevice;

    private final StorageConfigRepository storageConfigRepo;

    private volatile String selectedCameraPath;
    private volatile List<Map<String, String>> cachedCameraList;
    private volatile long cameraListCacheTime;
    private final Map<String, String> resolutionCache = new ConcurrentHashMap<>();

    public RecordingService(StorageConfigRepository storageConfigRepo) {
        this.storageConfigRepo = storageConfigRepo;
    }

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicLong recordingStartTime = new AtomicLong(0);

    private volatile Thread recordingThread;
    private volatile FFmpegFrameGrabber grabber;
    private volatile FFmpegFrameRecorder recorder;
    private volatile String currentFilePath;
    private volatile String currentOrderNo;

    /**
     * List available video cameras on the server.
     */
    public List<Map<String, String>> listCameras() {
        // Return cached list if fresh (< 60 seconds old)
        if (cachedCameraList != null && System.currentTimeMillis() - cameraListCacheTime < 60_000) {
            // Update selected flag
            for (var cam : cachedCameraList) {
                cam.put("selected", String.valueOf(cam.get("path").equals(getEffectiveCameraPath())));
            }
            return cachedCameraList;
        }

        List<Map<String, String>> cameras = new ArrayList<>();
        File devDir = new File("/dev");
        File[] videoDevices = devDir.listFiles((dir, name) -> name.startsWith("video"));
        if (videoDevices == null) return cameras;

        for (File dev : videoDevices) {
            String path = dev.getAbsolutePath();
            String name = getCameraName(path);
            // Only include devices that support video capture (skip metadata-only)
            if (!isVideoCaptureDevice(path)) continue;

            Map<String, String> cam = new HashMap<>();
            cam.put("path", path);
            cam.put("name", name);
            cam.put("resolution", resolutionCache.getOrDefault(path, ""));
            cam.put("selected", String.valueOf(path.equals(getEffectiveCameraPath())));
            cameras.add(cam);
        }

        cachedCameraList = cameras;
        cameraListCacheTime = System.currentTimeMillis();
        return cameras;
    }

    /**
     * Tests if a V4L2 device can actually capture video by listing its formats.
     * Also extracts max resolution info for display.
     */
    private boolean isVideoCaptureDevice(String devicePath) {
        // Step 1: List formats (fast, filters out metadata-only devices)
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "timeout", "3", "ffmpeg", "-f", "video4linux2",
                    "-list_formats", "all", "-i", devicePath
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                forceReleaseCamera(devicePath);
                return false;
            }
            byte[] outputBytes = p.getInputStream().readAllBytes();
            p.destroyForcibly();
            forceReleaseCamera(devicePath);

            String output = new String(outputBytes);
            boolean hasFormats = output.contains("Compressed") || output.contains("Raw")
                    || output.contains("mjpeg") || output.contains("yuyv422");
            if (!hasFormats) return false;

            String maxRes = extractMaxResolution(output);
            if (!maxRes.isEmpty()) {
                resolutionCache.put(devicePath, maxRes);
            }

            // Step 2: Quick capture test (filters out devices that list formats but can't capture)
            if (!canActuallyCapture(devicePath)) {
                log.info("Device {} lists formats but cannot capture frames - excluding", devicePath);
                return false;
            }

            log.info("Video capture device found: {}", devicePath);
            return true;
        } catch (Exception e) {
            log.debug("Device {} not a capture device: {}", devicePath, e.getMessage());
            return false;
        }
    }

    /**
     * Try to actually capture one frame from the device with a hard timeout.
     */
    private boolean canActuallyCapture(String devicePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "timeout", "3", "ffmpeg", "-f", "video4linux2",
                    "-i", devicePath, "-frames:v", "1", "-f", "null", "/dev/null"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                forceReleaseCamera(devicePath);
                return false;
            }
            p.destroyForcibly();
            forceReleaseCamera(devicePath);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String extractMaxResolution(String ffmpegOutput) {
        // Parse "1280x720", "1920x1080" etc from ffmpeg format listing
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4}x\\d{3,4})")
                .matcher(ffmpegOutput);
        int maxPixels = 0;
        String maxRes = "";
        while (m.find()) {
            String res = m.group(1);
            String[] parts = res.split("x");
            int pixels = Integer.parseInt(parts[0]) * Integer.parseInt(parts[1]);
            if (pixels > maxPixels) {
                maxPixels = pixels;
                maxRes = res;
            }
        }
        return maxRes;
    }

    private String getCameraName(String devicePath) {
        String devName = new File(devicePath).getName();

        // 1. Read from /sys/class/video4linux/.../name
        String baseName = devName;
        try {
            baseName = Files.readString(Path.of("/sys/class/video4linux", devName, "name")).trim();
        } catch (Exception ignored) {}

        // 2. Try to get USB vendor:product for better identification
        String usbInfo = getUsbInfo(devName);
        if (!usbInfo.isEmpty()) {
            baseName = baseName + " [" + usbInfo + "]";
        }

        return baseName + " @ " + devicePath;
    }

    /**
     * Read USB vendor/product ID from sysfs to identify the physical device.
     */
    private String getUsbInfo(String devName) {
        try {
            Path devicePath = Path.of("/sys/class/video4linux", devName, "device");
            // Walk up to find USB info (usually ../ for USB devices)
            Path parent = devicePath.toRealPath().getParent();
            if (parent == null) return "";

            Path vendorFile = parent.resolve("idVendor");
            Path productFile = parent.resolve("idProduct");
            if (Files.exists(vendorFile) && Files.exists(productFile)) {
                String vendor = Files.readString(vendorFile).trim();
                String product = Files.readString(productFile).trim();
                return vendor + ":" + product;
            }
        } catch (Exception ignored) {}
        return "";
    }

    public void setCameraDevice(String path) {
        this.selectedCameraPath = path;
        log.info("Camera device set to: {}", path);
    }

    public String getSelectedCamera() {
        return selectedCameraPath;
    }

    /**
     * Returns the effective camera path: user-selected, or default /dev/video{N}.
     */
    private String getEffectiveCameraPath() {
        if (selectedCameraPath != null && !selectedCameraPath.isBlank()) {
            return selectedCameraPath;
        }
        try {
            int device = Integer.parseInt(cameraDevice);
            return "/dev/video" + device;
        } catch (NumberFormatException e) {
            return cameraDevice; // already a full path
        }
    }

    /**
     * Start recording from the USB camera.
     *
     * @param orderNo    the order/barcode number
     * @param outputPath full path where the video file will be saved
     */
    public synchronized void startRecording(String orderNo, String outputPath) {
        if (recording.get()) {
            throw new IllegalStateException("Already recording");
        }

        this.currentOrderNo = orderNo;
        this.currentFilePath = outputPath;

        // Ensure parent directory exists
        try {
            Files.createDirectories(Path.of(outputPath).getParent());
        } catch (Exception e) {
            throw new RuntimeException("Cannot create output directory: " + outputPath, e);
        }

        recording.set(true);
        recordingStartTime.set(System.currentTimeMillis());

        // Read config outside thread
        StorageConfig recConfig = storageConfigRepo.findByStorageType("RECORDING")
                .orElse(null);
        int width = recConfig != null && recConfig.getVideoWidth() != null
                ? recConfig.getVideoWidth() : 640;
        int height = recConfig != null && recConfig.getVideoHeight() != null
                ? recConfig.getVideoHeight() : 480;
        int fps = recConfig != null && recConfig.getVideoFps() != null
                ? recConfig.getVideoFps() : 30;
        int bitrate = recConfig != null && recConfig.getVideoBitrate() != null
                ? recConfig.getVideoBitrate() : 5_000_000;

        recordingThread = new Thread(() -> {
            try {
                // Open camera
                String camPath = getEffectiveCameraPath();
                grabber = new FFmpegFrameGrabber(camPath);
                grabber.setFormat("video4linux2");
                grabber.setImageWidth(width);
                grabber.setImageHeight(height);
                grabber.setFrameRate(fps);
                grabber.start();

                // Setup recorder with actual grabbed resolution
                recorder = new FFmpegFrameRecorder(outputPath,
                        grabber.getImageWidth(), grabber.getImageHeight());
                recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264);
                recorder.setFormat("mp4");
                recorder.setFrameRate(fps);
                recorder.setVideoBitrate(bitrate);
                recorder.setVideoQuality(0);
                recorder.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P);
                recorder.start();

                log.info("Recording started: order={}, output={}, res={}x{}",
                        orderNo, outputPath, grabber.getImageWidth(), grabber.getImageHeight());

                // Capture loop
                Frame frame;
                while (recording.get() && (frame = grabber.grab()) != null) {
                    recorder.record(frame);
                }
            } catch (Exception e) {
                log.error("Recording error", e);
                recording.set(false);
            } finally {
                cleanup();
            }
        }, "recording-" + orderNo);

        recordingThread.start();
    }

    /**
     * Stop recording and return the path to the recorded file.
     */
    public synchronized String stopRecording() {
        if (!recording.get()) {
            throw new IllegalStateException("No active recording. The recording may have already stopped or the server was restarted.");
        }
        recording.set(false);
        recordingStartTime.set(0);

        try {
            recordingThread.join(5000); // wait up to 5s for thread to finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String filePath = currentFilePath;
        log.info("Recording stopped: order={}, file={}", currentOrderNo, filePath);

        currentOrderNo = null;
        currentFilePath = null;

        return filePath;
    }

    /**
     * Get current recording status.
     */
    public RecordingStatus getStatus() {
        if (!recording.get()) {
            return new RecordingStatus(false, null, 0, 0);
        }
        long elapsed = System.currentTimeMillis() - recordingStartTime.get();
        long fileSize = 0;
        try {
            File f = new File(currentFilePath);
            fileSize = f.exists() ? f.length() : 0;
        } catch (Exception ignored) {}
        return new RecordingStatus(true, currentOrderNo, elapsed / 1000, fileSize);
    }

    public boolean isRecording() {
        return recording.get();
    }

    /**
     * Capture a single photo frame from the USB camera.
     * If already recording, grabs from the active stream without interrupting.
     * Otherwise uses ffmpeg command line to capture one frame reliably.
     *
     * @return path to the saved JPEG file
     */
    public synchronized String capturePhoto(String orderNo, String outputPath) throws Exception {
        Files.createDirectories(Path.of(outputPath).getParent());

        if (recording.get() && grabber != null) {
            // Grab a frame from the active recording stream
            synchronized (grabber) {
                Frame frame = grabber.grab();
                if (frame == null) throw new RuntimeException("No frame captured from camera");
                saveFrameAsJpeg(frame, outputPath);
            }
        } else {
            // Only use the selected camera - no silent fallback
            String camPath = getEffectiveCameraPath();
            log.info("Taking photo with camera: {}", camPath);
            String error = captureWithFfmpeg(camPath, outputPath);
            if (error != null) {
                // Check if device is busy
                if (error.contains("Device or resource busy")) {
                    throw new RuntimeException("Camera is in use by another application. Please close other apps using the camera and try again.");
                }
                throw new RuntimeException("Failed to capture photo from " + camPath + ": " + error);
            }
        }

        log.info("Photo captured: order={}, path={}", orderNo, outputPath);
        return outputPath;
    }

    /**
     * Returns null on success, error message string on failure.
     */
    private String captureWithFfmpeg(String camPath, String outputPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "timeout", "8", "ffmpeg", "-f", "video4linux2", "-i", camPath,
                    "-frames:v", "1", "-update", "1", "-q:v", "2", "-y", outputPath
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                // Force-release the camera device if ffmpeg is stuck
                forceReleaseCamera(camPath);
                return "Photo capture timed out (camera may be busy)";
            }
            if (p.exitValue() != 0) {
                String err = new String(p.getInputStream().readAllBytes());
                if (err.contains("Device or resource busy")) {
                    forceReleaseCamera(camPath);
                    return "Device or resource busy";
                }
                if (err.contains("Inappropriate ioctl")) return "Device does not support video capture";
                if (err.contains("No such file")) return "Camera device not found";
                String[] lines = err.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    String line = lines[i].trim();
                    if (!line.isEmpty() && !line.startsWith("ffmpeg version")
                            && !line.startsWith("built with") && !line.startsWith("configuration:")
                            && !line.startsWith("lib")) {
                        return line;
                    }
                }
                return "ffmpeg error";
            }
            return null; // success
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * Forcefully release a V4L2 device by killing any process holding it.
     */
    private void forceReleaseCamera(String devicePath) {
        try {
            new ProcessBuilder("fuser", "-k", devicePath)
                    .redirectErrorStream(true).start()
                    .waitFor(3, TimeUnit.SECONDS);
            Thread.sleep(300);
        } catch (Exception ignored) {}
    }

    private void saveFrameAsJpeg(Frame frame, String outputPath) throws Exception {
        Java2DFrameConverter converter = new Java2DFrameConverter();
        BufferedImage image = converter.convert(frame);
        if (image == null) throw new RuntimeException("Failed to convert frame");
        ImageIO.write(image, "jpg", new File(outputPath));
        converter.close();
    }

    private void cleanup() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
            }
        } catch (Exception e) {
            log.warn("Error stopping recorder", e);
        }
        try {
            if (grabber != null) {
                grabber.stop();
                grabber.release();
                grabber = null;
            }
        } catch (Exception e) {
            log.warn("Error stopping grabber", e);
        }
    }

    /**
     * Recording status DTO.
     */
    public record RecordingStatus(boolean recording, String orderNo, long elapsedSec, long fileSizeBytes) {}
}
