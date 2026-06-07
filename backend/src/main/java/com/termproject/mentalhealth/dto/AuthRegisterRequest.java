package com.termproject.mentalhealth.dto;

import com.termproject.mentalhealth.domain.UserRole;
import jakarta.validation.constraints.NotBlank;

public record AuthRegisterRequest(
        @NotBlank String loginId,
        @NotBlank String password,
        UserRole role
) {
}
