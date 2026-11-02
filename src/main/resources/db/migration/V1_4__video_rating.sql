-- Each user's rating of a video, 1 to 5.
CREATE TABLE video_rating
(
    id       BIGINT      NOT NULL AUTO_INCREMENT,
    user_id  BIGINT      NOT NULL,
    video_id BIGINT      NOT NULL,
    rating   TINYINT     NOT NULL,
    rated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_video_rating UNIQUE (user_id, video_id),
    CONSTRAINT fk_video_rating_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_video_rating_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE,
    INDEX idx_video_rating_video (video_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
