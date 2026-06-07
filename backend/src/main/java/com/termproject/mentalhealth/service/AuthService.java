package com.termproject.mentalhealth.service;

import com.termproject.mentalhealth.domain.AppUser;
import com.termproject.mentalhealth.domain.ConsentLog;
import com.termproject.mentalhealth.domain.UserRole;
import com.termproject.mentalhealth.dto.AuthLoginRequest;
import com.termproject.mentalhealth.dto.AuthRegisterRequest;
import com.termproject.mentalhealth.dto.AuthResponse;
import com.termproject.mentalhealth.dto.UserResponse;
import com.termproject.mentalhealth.repository.ConsentLogRepository;
import com.termproject.mentalhealth.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final ConsentLogRepository consentLogRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, ConsentLogRepository consentLogRepository) {
        this.userRepository = userRepository;
        this.consentLogRepository = consentLogRepository;
    }

    @Transactional
    public AuthResponse register(AuthRegisterRequest request) {
        String loginId = request.loginId().trim();
        if (userRepository.existsByEmail(loginId)) {
            throw new IllegalArgumentException("이미 가입된 아이디입니다.");
        }

        AppUser user = new AppUser();
        user.setEmail(loginId);
        user.setName(loginId);
        user.setRole(request.role() == null ? UserRole.USER : request.role());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        AppUser saved = userRepository.save(user);

        saveConsent(saved, "privacy_and_sensitive_data", "v1");
        saveConsent(saved, "non_diagnostic_reference_use", "v1");

        return new AuthResponse(makeToken(saved), UserResponse.from(saved));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(AuthLoginRequest request) {
        AppUser user = userRepository.findByEmail(request.loginId().trim())
                .orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호를 확인해 주세요."));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("아이디 또는 비밀번호를 확인해 주세요.");
        }
        return new AuthResponse(makeToken(user), UserResponse.from(user));
    }

    private void saveConsent(AppUser user, String consentType, String version) {
        ConsentLog consent = new ConsentLog();
        consent.setUser(user);
        consent.setConsentType(consentType);
        consent.setConsentVersion(version);
        consent.setAgreed(true);
        consentLogRepository.save(consent);
    }

    private String makeToken(AppUser user) {
        return "mvp-token-" + user.getId();
    }
}
