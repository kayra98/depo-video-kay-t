package com.kayra.vk.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

/**
 * Intercepts POST /login requests, extracts email + TOTP code,
 * and delegates authentication to the AuthenticationManager.
 */
public class EmailTotpAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    public EmailTotpAuthenticationFilter(AuthenticationManager authenticationManager) {
        super(request -> "/login".equals(request.getServletPath()) && "POST".equalsIgnoreCase(request.getMethod()),
              authenticationManager);
        setAuthenticationSuccessHandler(new SimpleUrlAuthenticationSuccessHandler("/index"));
        setAuthenticationFailureHandler(new SimpleUrlAuthenticationFailureHandler("/login?error"));
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request,
                                                 HttpServletResponse response)
            throws AuthenticationException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            throw new AuthenticationServiceException(
                    "Authentication method not supported: " + request.getMethod());
        }

        String email = request.getParameter("email");
        String totpCode = request.getParameter("totpCode");

        if (email == null || totpCode == null) {
            throw new AuthenticationServiceException("Missing email or TOTP code parameter");
        }

        email = email.trim();
        totpCode = totpCode.trim();

        EmailTotpAuthenticationToken authRequest =
                new EmailTotpAuthenticationToken(email, totpCode);

        setDetails(request, authRequest);

        return this.getAuthenticationManager().authenticate(authRequest);
    }

    protected void setDetails(HttpServletRequest request,
                               EmailTotpAuthenticationToken authRequest) {
        authRequest.setDetails(this.authenticationDetailsSource.buildDetails(request));
    }
}
