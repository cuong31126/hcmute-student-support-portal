package com.school.counseling.config;

import com.school.counseling.module.auth.service.JwtAuthenticationFilter;
import com.school.counseling.module.auth.service.UserDetailsServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authenticationProvider(authenticationProvider())
            .authorizeHttpRequests(auth -> auth
                // 1. Khu vực Cán bộ & Quản trị viên
                .requestMatchers(
                    "/staff/**",
                    "/feed/official/create",
                    "/moderation/**"
                ).hasAnyRole("STAFF", "ADMIN")

                .requestMatchers(
                    "/admin/**",
                    "/api/admin/**"
                ).hasRole("ADMIN")

                // 2. Khu vực Tương tác Sinh viên / Người dùng đã xác thực
                .requestMatchers(
                    "/student/**",
                    "/tickets/create",
                    "/tickets/my-tickets",
                    "/feed/forum/create",
                    "/feed/forum/*/comment",
                    "/feed/forum/*/like",
                    "/feed/forum/*/report"
                ).authenticated()

                // 3. Khu vực công khai cho Guest / Thí sinh / Tài nguyên tĩnh & Đọc tin tức
                .requestMatchers(
                    "/",
                    "/public/**",
                    "/auth/**",
                    "/faqs/**",
                    "/feed/official",
                    "/feed/official/*",
                    "/feed/forum",
                    "/image/**",
                    "/documents/**",
                    "/videos/**",
                    "/css/**",
                    "/js/**",
                    "/images/**",
                    "/webjars/**",
                    "/ws-chat/**",
                    "/api/v1/faqs/**",
                    "/api/v1/ai/**",
                    "/api/v1/auth/**",
                    "/api/v1/integration/**",
                    "/tickets/guest-track/**"
                ).permitAll()

                // Các request còn lại yêu cầu xác thực
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/auth/login")
                .loginProcessingUrl("/auth/login")
                .defaultSuccessUrl("/", false)
                .failureUrl("/auth/login?error=true")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/auth/logout")
                .logoutSuccessUrl("/auth/login?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**", "/ws-chat/**")
            )
            .exceptionHandling(ex -> ex
                .accessDeniedPage("/error/403")
            )
            // Tích hợp JWT Filter để hỗ trợ cả REST API Client lẫn Session Cookie Browser
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
