package com.kayra.vk.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Custom authentication token for email + TOTP authentication.
 * Before authentication: holds email (principal) and TOTP code (credentials).
 * After authentication: holds email and role-based authorities, credentials erased.
 */
public class EmailTotpAuthenticationToken extends AbstractAuthenticationToken {

    private final String principal;   // email address
    private String credentials;       // TOTP code (erased after auth)

    /**
     * Unauthenticated — used by the filter before authentication.
     */
    public EmailTotpAuthenticationToken(String email, String totpCode) {
        super(new ArrayList<>());
        this.principal = email;
        this.credentials = totpCode;
        setAuthenticated(false);
    }

    /**
     * Authenticated — used by the provider after successful validation.
     */
    public EmailTotpAuthenticationToken(String email, Collection<? extends GrantedAuthority> authorities) {
        super(new ArrayList<>(authorities));
        this.principal = email;
        this.credentials = null;
        setAuthenticated(true);
    }

    /**
     * Convenience factory: creates an authenticated token with a single ROLE_* authority.
     */
    public static EmailTotpAuthenticationToken authenticated(String email, String role) {
        return new EmailTotpAuthenticationToken(email,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Override
    public String getCredentials() {
        return credentials;
    }

    @Override
    public String getPrincipal() {
        return principal;
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        this.credentials = null;
    }
}
