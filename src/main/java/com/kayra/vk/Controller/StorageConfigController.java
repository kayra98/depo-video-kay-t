package com.kayra.vk.Controller;

import com.kayra.vk.Model.StorageConfig;
import com.kayra.vk.Repository.StorageConfigRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/settings")
public class StorageConfigController {

    private final StorageConfigRepository configRepo;

    public StorageConfigController(StorageConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @GetMapping("/storage")
    public String settingsPage(Model model) {
        StorageConfig localConfig = configRepo.findByStorageType("LOCAL")
                .orElse(StorageConfig.builder().storageType("LOCAL").enabled(true).build());
        StorageConfig s3Config = configRepo.findByStorageType("S3")
                .orElse(StorageConfig.builder().storageType("S3").enabled(false).build());
        StorageConfig recConfig = configRepo.findByStorageType("RECORDING")
                .orElse(null);

        model.addAttribute("pageTitle", "Storage Settings");
        model.addAttribute("localConfig", localConfig);
        model.addAttribute("s3Config", s3Config);
        model.addAttribute("recConfig", recConfig);
        return "settings";
    }

    @PostMapping("/storage")
    public String saveSettings(
            // Local
            @RequestParam(value = "localEnabled", defaultValue = "true") boolean localEnabled,
            @RequestParam(value = "localPath", defaultValue = "./recordings") String localPath,
            @RequestParam(value = "localMaxPercent", defaultValue = "80") int localMaxPercent,
            // S3
            @RequestParam(value = "s3Enabled", defaultValue = "false") boolean s3Enabled,
            @RequestParam(value = "s3Endpoint", defaultValue = "") String s3Endpoint,
            @RequestParam(value = "s3Region", defaultValue = "de") String s3Region,
            @RequestParam(value = "s3Bucket", defaultValue = "") String s3Bucket,
            @RequestParam(value = "s3AccessKey", defaultValue = "") String s3AccessKey,
            @RequestParam(value = "s3SecretKey", defaultValue = "") String s3SecretKey,
            @RequestParam(value = "s3PublicUrl", defaultValue = "") String s3PublicUrl,
            @RequestParam(value = "s3RetentionDays", defaultValue = "30") int s3RetentionDays,
            // Recording quality
            @RequestParam(value = "videoWidth", defaultValue = "1280") int videoWidth,
            @RequestParam(value = "videoHeight", defaultValue = "720") int videoHeight,
            @RequestParam(value = "videoFps", defaultValue = "30") int videoFps,
            @RequestParam(value = "videoBitrate", defaultValue = "5000000") int videoBitrate,
            RedirectAttributes redirectAttributes) {

        // Save local config
        StorageConfig localConfig = configRepo.findByStorageType("LOCAL")
                .orElse(StorageConfig.builder().storageType("LOCAL").build());
        localConfig.setEnabled(localEnabled);
        localConfig.setLocalPath(localPath);
        localConfig.setLocalMaxPercent(localMaxPercent);
        configRepo.save(localConfig);

        // Save S3 config
        StorageConfig s3Config = configRepo.findByStorageType("S3")
                .orElse(StorageConfig.builder().storageType("S3").build());
        s3Config.setEnabled(s3Enabled);
        s3Config.setS3Endpoint(s3Endpoint);
        s3Config.setS3Region(s3Region);
        s3Config.setS3Bucket(s3Bucket);
        s3Config.setS3AccessKey(s3AccessKey);
        s3Config.setS3SecretKey(s3SecretKey);
        s3Config.setS3PublicUrl(s3PublicUrl);
        s3Config.setS3RetentionDays(s3RetentionDays);
        configRepo.save(s3Config);

        // Save recording quality config
        StorageConfig recConfig = configRepo.findByStorageType("RECORDING")
                .orElse(StorageConfig.builder().storageType("RECORDING").build());
        recConfig.setVideoWidth(videoWidth);
        recConfig.setVideoHeight(videoHeight);
        recConfig.setVideoFps(videoFps);
        recConfig.setVideoBitrate(videoBitrate);
        configRepo.save(recConfig);

        redirectAttributes.addFlashAttribute("saved", true);
        return "redirect:/settings/storage";
    }
}
