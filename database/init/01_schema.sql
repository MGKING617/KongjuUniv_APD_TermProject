CREATE TABLE IF NOT EXISTS app_users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  email VARCHAR(190) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  name VARCHAR(80) NOT NULL,
  role VARCHAR(30) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS consent_logs (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  consent_type VARCHAR(80) NOT NULL,
  consent_version VARCHAR(40) NOT NULL,
  agreed BIT NOT NULL,
  agreed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_consent_user (user_id),
  CONSTRAINT fk_consent_user FOREIGN KEY (user_id) REFERENCES app_users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_sessions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL,
  started_at DATETIME(6) NOT NULL,
  ended_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  KEY idx_chat_sessions_user (user_id),
  CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES app_users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_messages (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  sender VARCHAR(20) NOT NULL,
  content LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_chat_messages_session (session_id),
  CONSTRAINT fk_chat_message_session FOREIGN KEY (session_id) REFERENCES chat_sessions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assessments (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  session_id BIGINT NULL,
  phq_like_score INT NOT NULL,
  ml_risk_percent DOUBLE NOT NULL,
  final_score DOUBLE NOT NULL,
  risk_level VARCHAR(30) NOT NULL,
  summary VARCHAR(1200) NOT NULL,
  domain_scores_json VARCHAR(2400) NULL,
  factor_contributions_json VARCHAR(3000) NULL,
  global_importance_json VARCHAR(2400) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_assessments_user_created (user_id, created_at),
  KEY idx_assessments_session (session_id),
  CONSTRAINT fk_assessment_user FOREIGN KEY (user_id) REFERENCES app_users (id),
  CONSTRAINT fk_assessment_session FOREIGN KEY (session_id) REFERENCES chat_sessions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assessment_signals (
  assessment_id BIGINT NOT NULL,
  signal_text VARCHAR(80) NOT NULL,
  KEY idx_assessment_signals_assessment (assessment_id),
  CONSTRAINT fk_assessment_signal_assessment FOREIGN KEY (assessment_id) REFERENCES assessments (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS risk_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  session_id BIGINT NULL,
  risk_type VARCHAR(80) NOT NULL,
  severity VARCHAR(30) NOT NULL,
  evidence_text LONGTEXT NOT NULL,
  action_taken VARCHAR(1000) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_risk_events_created (created_at),
  KEY idx_risk_events_user (user_id),
  KEY idx_risk_events_session (session_id),
  CONSTRAINT fk_risk_event_user FOREIGN KEY (user_id) REFERENCES app_users (id),
  CONSTRAINT fk_risk_event_session FOREIGN KEY (session_id) REFERENCES chat_sessions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
