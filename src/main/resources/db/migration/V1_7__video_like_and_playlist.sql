-- Videos a user likes.
CREATE TABLE video_like
(
    id       BIGINT      NOT NULL AUTO_INCREMENT,
    user_id  BIGINT      NOT NULL,
    video_id BIGINT      NOT NULL,
    liked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_video_like UNIQUE (user_id, video_id),
    CONSTRAINT fk_video_like_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_video_like_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE,
    INDEX idx_video_like_video (video_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- A user's own lists of whole videos and clips (a chapter of a video), in an order of their own. Public ones can be
-- played by everyone.
CREATE TABLE playlist
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    uuid        CHAR(36)      NOT NULL,
    user_id     BIGINT        NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    description VARCHAR(4000) NULL,
    public      BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_playlist_uuid UNIQUE (uuid),
    CONSTRAINT fk_playlist_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    INDEX idx_playlist_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- A whole video (no start) or a clip from start_seconds to end_seconds, titled as the chapter was when it was added,
-- since chapters are replaced when edited.
CREATE TABLE playlist_item
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    uuid          CHAR(36)     NOT NULL,
    playlist_id   BIGINT       NOT NULL,
    position      INT          NOT NULL,
    video_id      BIGINT       NOT NULL,
    start_seconds INT          NULL,
    end_seconds   INT          NULL,
    title         VARCHAR(255) NULL,
    added_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_playlist_item_uuid UNIQUE (uuid),
    CONSTRAINT fk_playlist_item_playlist FOREIGN KEY (playlist_id) REFERENCES playlist (id) ON DELETE CASCADE,
    CONSTRAINT fk_playlist_item_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE,
    INDEX idx_playlist_item_playlist (playlist_id, position),
    INDEX idx_playlist_item_video (video_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
