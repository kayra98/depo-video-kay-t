package com.kayra.vk.config;

import com.kayra.vk.security.EmailTotpAuthenticationFilter;
import com.kayra.vk.security.EmailTotpAuthenticationProvider;
import com.kayra.vk.security.SetupCheckFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final EmailTotpAuthenticationProvider authProvider;
    private final SetupCheckFilter setupCheckFilter;

    public SecurityConfig(EmailTotpAuthenticationProvider authProvider,
                          SetupCheckFilter setupCheckFilter) {
        this.authProvider = authProvider;
        this.setupCheckFilter = setupCheckFilter;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public EmailTotpAuthenticationFilter emailTotpAuthenticationFilter(
            AuthenticationManager authenticationManager) {
        EmailTotpAuthenticationFilter filter =
                new EmailTotpAuthenticationFilter(authenticationManager);
        filter.setSecurityContextRepository(securityContextRepository());
        return filter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            EmailTotpAuthenticationFilter authFilter)
            throws Exception {

        authFilter.setSecurityContextRepository(securityContextRepository());

        http
            .securityContext(context -> context
                .securityContextRepository(securityContextRepository()))
            .authenticationProvider(authProvider)
            .addFilterBefore(setupCheckFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAt(authFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/setup", "/login", "/css/**", "/js/**",
                        "/images/**", "/error", "/favicon.ico").permitAll()
                .requestMatchers("/settings/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/record/upload", "/api/record/photo",
                        "/api/record/start", "/api/record/stop")
            )
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("SESSION")
                .permitAll()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation().migrateSession()
                .maximumSessions(10)
            );

        return http.build();
    }
}
