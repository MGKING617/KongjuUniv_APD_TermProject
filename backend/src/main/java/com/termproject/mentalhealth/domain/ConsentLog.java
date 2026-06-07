package com.termproject.mentalhealth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "consent_logs")
public class ConsentLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(nullable = false, length = 80)
    private String consentType;

    @Column(nullable = false, length = 40)
    private String consentVersion;

    @Column(nullable = false)
    private boolean agreed;

    @Column(nullable = false)
    private LocalDateTime agreedAt;

    @PrePersist
    void prePersist() {
        agreedAt = LocalDateTime.now();
    }

    public void setUser(AppUser user) {
        this.user = user;
    }

    public void setConsentType(String consentType) {
        this.consentType = consentType;
    }

    public void setConsentVersion(String consentVersion) {
        this.consentVersion = consentVersion;
    }

    public void setAgreed(boolean agreed) {
        this.agreed = agreed;
    }
}
