package dev.huanling.traderecon.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  BCryptPasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(@Value("${app.demo-password}") String password) {
    if (password.length() < 12)
      throw new IllegalArgumentException("DEMO_PASSWORD must have at least 12 characters");
    var encoder = new BCryptPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername("viewer").password(encoder.encode(password)).roles("VIEWER").build(),
        User.withUsername("analyst").password(encoder.encode(password)).roles("ANALYST").build(),
        User.withUsername("supervisor")
            .password(encoder.encode(password))
            .roles("SUPERVISOR")
            .build());
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/login")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/api/imports",
                        "/imports",
                        "/api/runs",
                        "/api/reconciliation-runs",
                        "/api/reconciliation-runs/*/retry",
                        "/api/runs/*/retry",
                        "/runs/*/retry",
                        "/api/cases/*/claim",
                        "/api/cases/*/ai-analysis",
                        "/cases/*/ai-analysis",
                        "/api/cases/*/decisions",
                        "/cases/*/decisions",
                        "/datasets",
                        "/runs")
                    .hasAnyRole("ANALYST", "SUPERVISOR")
                    .anyRequest()
                    .authenticated())
        .formLogin(f -> f.defaultSuccessUrl("/", true))
        .exceptionHandling(
            e ->
                e.defaultAuthenticationEntryPointFor(
                        (req, res, ex) ->
                            dev.huanling.traderecon.web.ApiErrors.write(
                                res, 401, "UNAUTHENTICATED"),
                        req -> req.getRequestURI().startsWith("/api/"))
                    .defaultAuthenticationEntryPointFor(
                        new org.springframework.security.web.authentication
                            .LoginUrlAuthenticationEntryPoint("/login"),
                        req -> !req.getRequestURI().startsWith("/api/"))
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          if (req.getRequestURI().startsWith("/api/"))
                            dev.huanling.traderecon.web.ApiErrors.write(res, 403, "FORBIDDEN");
                          else res.sendError(403);
                        }))
        .build();
  }
}
