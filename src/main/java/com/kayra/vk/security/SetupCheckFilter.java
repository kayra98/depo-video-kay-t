package com.kayra.vk.security;

import com.kayra.vk.Repository.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Runs before the Spring Security filter chain.
 * If no users exist in the database, redirects all requests to /setup
 * (except /setup itself and static resources).
 * Once setup is complete (at least one user exists), this filter becomes a no-op.
 */
@Component
public class SetupCheckFilter implements Filter {

    private static final Set<String> SKIP_PATHS = Set.of(
            "/setup", "/error"
    );

    private static final Set<String> SKIP_PREFIXES = Set.of(
            "/css/", "/js/", "/images/", "/favicon.ico"
    );

    private final UserRepository userRepository;

    public SetupCheckFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getServletPath();

        // Always allow the setup page and static resources
        if (SKIP_PATHS.contains(path)) {
            chain.doFilter(req, res);
            return;
        }
        for (String prefix : SKIP_PREFIXES) {
            if (path.startsWith(prefix)) {
                chain.doFilter(req, res);
                return;
            }
        }

        // Check if setup is needed (cached after first DB query)
        if (!isSetupComplete()) {
            response.sendRedirect(request.getContextPath() + "/setup");
            return;
        }

        chain.doFilter(req, res);
    }

    private boolean isSetupComplete() {
        return userRepository.count() > 0;
    }
}
