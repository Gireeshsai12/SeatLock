package com.seatlock;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class Security {
  static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Bean
  BCryptPasswordEncoder encoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity h, JdbcTemplate db) throws Exception {
    return h.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(
                        jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/api/auth/login", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(
                        "/api/ops/**", "/api/lab/**", "/api/logs/**", "/actuator/prometheus")
                    .hasAnyRole("OPERATOR", "ADMIN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((q, r, x) -> r.sendError(401))
                    .accessDeniedHandler((q, r, x) -> r.sendError(403)))
        .addFilterBefore(
            new OncePerRequestFilter() {
              protected void doFilterInternal(
                  HttpServletRequest q, HttpServletResponse r, FilterChain c)
                  throws ServletException, IOException {
                String correlation = UUID.randomUUID().toString();
                r.setHeader("X-Correlation-ID", correlation);
                q.setAttribute("correlation", correlation);
                String v = q.getHeader("Authorization");
                if (v != null && v.startsWith("Bearer ")) {
                  var found =
                      db.queryForList(
                          "select u.id,u.role from auth_token t join app_user u on u.id=t.user_id"
                              + " where t.token=? and t.expires_at>clock_timestamp()",
                          hash(v.substring(7)));
                  if (!found.isEmpty()) {
                    var u = found.getFirst();
                    SecurityContextHolder.getContext()
                        .setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                u.get("id").toString(),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + u.get("role")))));
                  }
                }
                try {
                  c.doFilter(q, r);
                } finally {
                  org.slf4j.LoggerFactory.getLogger(Security.class)
                      .info(
                          "request correlation={} method={} status={}",
                          correlation,
                          q.getMethod(),
                          r.getStatus());
                  SecurityContextHolder.clearContext();
                }
              }
            },
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
