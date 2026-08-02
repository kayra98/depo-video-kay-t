package com.kayra.vk.Controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.kayra.vk.Model.User;
import com.kayra.vk.Repository.UserRepository;
import com.kayra.vk.Service.TotpService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

@Controller
@RequestMapping("/setup")
public class SetupController {

    private static final String SETUP_SECRET_KEY = "setupTotpSecret";
    private static final int QR_SIZE = 250;

    private final TotpService totpService;
    private final UserRepository userRepository;

    public SetupController(TotpService totpService,
                           UserRepository userRepository) {
        this.totpService = totpService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String showSetupForm(Model model, HttpSession session) {
        // If users already exist, redirect to login
        if (userRepository.count() > 0) {
            return "redirect:/login";
        }

        // Generate TOTP secret and store in session
        String secret = totpService.generateSecret();
        session.setAttribute(SETUP_SECRET_KEY, secret);

        // Build provisioning URI and generate QR code
        String provisioningUri = totpService.buildProvisioningUri("user", secret);
        String qrCodeBase64 = generateQrCode(provisioningUri);

        model.addAttribute("qrCode", qrCodeBase64);
        model.addAttribute("pageTitle", "Initial Setup");
        return "setup";
    }

    @PostMapping
    public String processSetup(@RequestParam String email,
                               @RequestParam String totpCode,
                               HttpSession session,
                               Model model) {
        String secret = (String) session.getAttribute(SETUP_SECRET_KEY);
        if (secret == null) {
            return "redirect:/setup";
        }

        // Validate email
        if (email == null || email.isBlank()) {
            model.addAttribute("error", "Email address is required.");
            model.addAttribute("qrCode", generateQrCode(
                    totpService.buildProvisioningUri("user", secret)));
            return "setup";
        }

        // Check if email already used
        if (userRepository.existsByEmail(email.trim())) {
            model.addAttribute("error", "This email is already registered.");
            model.addAttribute("qrCode", generateQrCode(
                    totpService.buildProvisioningUri("user", secret)));
            return "setup";
        }

        // Validate TOTP code
        if (!totpService.validateCode(secret, totpCode.trim())) {
            model.addAttribute("error", "Invalid TOTP code. Please try again.");
            String provisioningUri = totpService.buildProvisioningUri("user", secret);
            model.addAttribute("qrCode", generateQrCode(provisioningUri));
            return "setup";
        }

        // Create and save user
        User user = User.builder()
                .email(email.trim())
                .totpSecret(secret)
                .role(User.Role.ADMIN)
                .build();
        userRepository.save(user);

        session.removeAttribute(SETUP_SECRET_KEY);
        return "redirect:/login?setupComplete";
    }

    private String generateQrCode(String text) {
        try {
            QRCodeWriter qrWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrWriter.encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE);
            ByteArrayOutputStream pngStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngStream);
            return Base64.getEncoder().encodeToString(pngStream.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate QR code", e);
        }
    }
}
