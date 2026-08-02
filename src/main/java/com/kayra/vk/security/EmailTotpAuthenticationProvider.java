package com.kayra.vk.security;

import com.kayra.vk.Model.User;
import com.kayra.vk.Repository.UserRepository;
import com.kayra.vk.Service.TotpService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Validates email + TOTP code against the database.
 * Looks up the user by email, then verifies the TOTP code against their stored secret.
 */
@Component
public class EmailTotpAuthenticationProvider implements AuthenticationProvider {

    private final UserRepository userRepository;
    private final TotpService totpService;

    public EmailTotpAuthenticationProvider(UserRepository userRepository, TotpService totpService) {
        this.userRepository = userRepository;
        this.totpService = totpService;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return EmailTotpAuthenticationToken.class.isAssignableFrom(authentication);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getPrincipal().toString();
        String totpCode = authentication.getCredentials().toString();

        if (email == null || email.isBlank() || totpCode == null || totpCode.isBlank()) {
            throw new BadCredentialsException("Email and TOTP code are required");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or TOTP code"));

        if (!totpService.validateCode(user.getTotpSecret(), totpCode)) {
            throw new BadCredentialsException("Invalid email or TOTP code");
        }

        return EmailTotpAuthenticationToken.authenticated(email, user.getRole().name());
    }
}
