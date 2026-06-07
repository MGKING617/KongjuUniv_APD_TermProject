package com.termproject.mentalhealth.dto;

import com.termproject.mentalhealth.domain.AppUser;
import com.termproject.mentalhealth.domain.UserRole;

public record UserResponse(Long id, String email, String name, UserRole role) {
    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }
}
