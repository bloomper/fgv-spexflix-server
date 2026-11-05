-- Who takes part in a video ("Medverkande"), in the order shown: a name and, optionally, what they do or play,
-- e.g. "Kleopatra" or "Regi". Entered by editors; the credits pages list everyone with their videos.
CREATE TABLE video_credit
(
    video_id BIGINT       NOT NULL,
    position INT          NOT NULL,
    name     VARCHAR(255) NOT NULL,
    role     VARCHAR(255) NULL,
    PRIMARY KEY (video_id, position),
    CONSTRAINT fk_video_credit_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE,
    INDEX idx_video_credit_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
