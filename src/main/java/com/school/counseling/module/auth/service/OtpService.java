package com.school.counseling.module.auth.service;

import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.notification.service.EmailAsyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private final UserRepository userRepository;
    private final EmailAsyncService emailAsyncService;
    private final PasswordEncoder passwordEncoder;

    private static final long OTP_VALID_DURATION_SECONDS = 300; // 5 phút

    private static class OtpEntry {
        final String code;
        final Instant expiresAt;

        OtpEntry(String code, Instant expiresAt) {
            this.code = code;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final Map<String, OtpEntry> otpStorage = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Tạo mã OTP 6 số ngẫu nhiên và gửi tới Gmail của người dùng
     */
    public boolean generateAndSendOtpForPasswordReset(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }

        Optional<User> userOpt = userRepository.findByEmail(email.trim());
        if (userOpt.isEmpty()) {
            log.warn("Yêu cầu gửi OTP quên mật khẩu thất bại: Email {} không tồn tại trong hệ thống", email);
            return false;
        }

        User user = userOpt.get();
        String otpCode = String.format("%06d", secureRandom.nextInt(1_000_000));
        Instant expiresAt = Instant.now().plusSeconds(OTP_VALID_DURATION_SECONDS);

        otpStorage.put(email.trim().toLowerCase(), new OtpEntry(otpCode, expiresAt));

        log.info("Đã tạo OTP xác thực cho {}: [***] (Hạn 5 phút)", email);
        emailAsyncService.sendOtpEmail(user.getEmail(), user.getFullName(), otpCode, "đặt lại mật khẩu tài khoản");
        return true;
    }

    /**
     * Xác thực mã OTP
     */
    public boolean verifyOtp(String email, String code) {
        if (email == null || code == null) return false;
        String key = email.trim().toLowerCase();
        OtpEntry entry = otpStorage.get(key);

        if (entry == null) {
            return false;
        }

        if (entry.isExpired()) {
            otpStorage.remove(key);
            return false;
        }

        return entry.code.equals(code.trim());
    }

    /**
     * Đặt lại mật khẩu mới sau khi xác thực OTP hợp lệ
     */
    @Transactional
    public boolean resetPasswordWithOtp(String email, String otpCode, String newPassword) {
        if (!verifyOtp(email, otpCode)) {
            return false;
        }

        Optional<User> userOpt = userRepository.findByEmail(email.trim());
        if (userOpt.isEmpty()) {
            return false;
        }

        User user = userOpt.get();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Xóa OTP sau khi sử dụng thành công
        otpStorage.remove(email.trim().toLowerCase());
        log.info("Đặt lại mật khẩu thành công cho tài khoản: {}", user.getUsername());
        return true;
    }
}
