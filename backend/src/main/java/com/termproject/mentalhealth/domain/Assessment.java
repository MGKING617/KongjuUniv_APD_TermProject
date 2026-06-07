package com.termproject.mentalhealth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "assessments")
public class Assessment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private ChatSession session;

    @Column(nullable = false)
    private int phqLikeScore;

    @Column(nullable = false)
    private double mlRiskPercent;

    @Column(nullable = false)
    private double finalScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RiskLevel riskLevel;

    @ElementCollection
    @CollectionTable(name = "assessment_signals", joinColumns = @JoinColumn(name = "assessment_id"))
    @Column(name = "signal_text", length = 80)
    private List<String> detectedSignals = new ArrayList<>();

    @Column(nullable = false, length = 1200)
    private String summary;

    @Column(length = 2400)
    private String domainScoresJson;

    @Column(length = 3000)
    private String factorContributionsJson;

    @Column(length = 2400)
    private String globalImportanceJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public void setUser(AppUser user) {
        this.user = user;
    }

    public ChatSession getSession() {
        return session;
    }

    public void setSession(ChatSession session) {
        this.session = session;
    }

    public int getPhqLikeScore() {
        return phqLikeScore;
    }

    public void setPhqLikeScore(int phqLikeScore) {
        this.phqLikeScore = phqLikeScore;
    }

    public double getMlRiskPercent() {
        return mlRiskPercent;
    }

    public void setMlRiskPercent(double mlRiskPercent) {
        this.mlRiskPercent = mlRiskPercent;
    }

    public double getFinalScore() {
        return finalScore;
    }

    public void setFinalScore(double finalScore) {
        this.finalScore = finalScore;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public List<String> getDetectedSignals() {
        return detectedSignals;
    }

    public void setDetectedSignals(List<String> detectedSignals) {
        this.detectedSignals = detectedSignals;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDomainScoresJson() {
        return domainScoresJson;
    }

    public void setDomainScoresJson(String domainScoresJson) {
        this.domainScoresJson = domainScoresJson;
    }

    public String getFactorContributionsJson() {
        return factorContributionsJson;
    }

    public void setFactorContributionsJson(String factorContributionsJson) {
        this.factorContributionsJson = factorContributionsJson;
    }

    public String getGlobalImportanceJson() {
        return globalImportanceJson;
    }

    public void setGlobalImportanceJson(String globalImportanceJson) {
        this.globalImportanceJson = globalImportanceJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
