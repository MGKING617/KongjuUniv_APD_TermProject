package com.termproject.mentalhealth.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthLoginRequest(
        @NotBlank String loginId,
        @NotBlank String password
) {
}
