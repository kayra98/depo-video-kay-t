package com.kayra.vk.Service;

import org.jboss.aerogear.security.otp.Totp;
import org.jboss.aerogear.security.otp.api.Base32;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class TotpService {

    private static final String ISSUER = "vk";

    /**
     * Generate a new random Base32-encoded TOTP secret.
     */
    public String generateSecret() {
        return Base32.random();
    }

    /**
     * Build the otpauth:// provisioning URI for QR code generation.
     * Format: otpauth://totp/{issuer}:{account}?secret={secret}&issuer={issuer}
     */
    public String buildProvisioningUri(String email, String secret) {
        String label = URLEncoder.encode(ISSUER + ":" + email, StandardCharsets.UTF_8);
        return String.format("otpauth://totp/%s?secret=%s&issuer=%s", label, secret, ISSUER);
    }

    /**
     * Validate a TOTP code against the stored secret.
     * Uses default time window of +/- 1 step (30s) to account for clock drift.
     */
    public boolean validateCode(String secret, String code) {
        try {
            Totp totp = new Totp(secret);
            return totp.verify(code);
        } catch (Exception e) {
            return false;
        }
    }
}
