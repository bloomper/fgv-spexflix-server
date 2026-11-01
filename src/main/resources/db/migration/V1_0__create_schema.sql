CREATE TABLE app_user
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    subject      VARCHAR(64)  NOT NULL,
    username     VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NULL,
    created_at   DATETIME(6)  NOT NULL,
    last_seen_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_subject UNIQUE (subject),
    CONSTRAINT uk_app_user_username UNIQUE (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE video
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    uuid               CHAR(36)     NOT NULL,
    spex_id            BIGINT       NULL,
    spex_title         VARCHAR(255) NULL,
    spex_year          SMALLINT     NULL,
    spex_revival       BIT          NULL,
    spex_category_id   BIGINT       NULL,
    spex_category_name VARCHAR(255) NULL,
    spex_refreshed_at  DATETIME(6)  NULL,
    title              VARCHAR(255) NOT NULL,
    year               SMALLINT     NULL,
    kind               VARCHAR(30)  NOT NULL,
    performance        VARCHAR(255) NULL,
    performance_date   DATE         NULL,
    description        TEXT         NULL,
    status             VARCHAR(20)  NOT NULL,
    failure            VARCHAR(1000) NULL,
    original_name      VARCHAR(255) NULL,
    original_extension VARCHAR(10)  NULL,
    original_size      BIGINT       NULL,
    original_kept      BIT          NOT NULL DEFAULT 1,
    duration_ms        BIGINT       NULL,
    width              INT          NULL,
    height             INT          NULL,
    generation         INT          NOT NULL DEFAULT 0,
    has_thumbnails     BIT          NOT NULL DEFAULT 0,
    poster_seconds     DOUBLE       NULL,
    poster_uploaded    BIT          NOT NULL DEFAULT 0,
    published          BIT          NOT NULL DEFAULT 0,
    published_at       DATETIME(6)  NULL,
    trashed_at         DATETIME(6)  NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_by         VARCHAR(255) NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    last_modified_by   VARCHAR(255) NULL,
    last_modified_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_video_uuid UNIQUE (uuid),
    INDEX idx_video_spex (spex_id),
    INDEX idx_video_year (year)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE rendition
(
    id           BIGINT NOT NULL AUTO_INCREMENT,
    video_id     BIGINT NOT NULL,
    height       INT    NOT NULL,
    width        INT    NOT NULL,
    bitrate_kbps INT    NOT NULL,
    copied       BIT    NOT NULL DEFAULT 0,
    size         BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_rendition_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE chapter
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    video_id             BIGINT       NOT NULL,
    start_seconds        INT          NOT NULL,
    title                VARCHAR(255) NOT NULL,
    spexify_track_id     BIGINT       NULL,
    spexify_track_title  VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chapter_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE,
    INDEX idx_chapter_track (spexify_track_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE subtitle
(
    id       BIGINT      NOT NULL AUTO_INCREMENT,
    video_id BIGINT      NOT NULL,
    language VARCHAR(10) NOT NULL,
    label    VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_subtitle_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE,
    CONSTRAINT uk_subtitle_language UNIQUE (video_id, language)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE processing_job
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    video_id    BIGINT        NOT NULL,
    status      VARCHAR(20)   NOT NULL,
    source      VARCHAR(255)  NOT NULL,
    progress    INT           NOT NULL DEFAULT 0,
    step        VARCHAR(50)   NULL,
    attempts    INT           NOT NULL DEFAULT 0,
    error       VARCHAR(2000) NULL,
    created_at  DATETIME(6)   NOT NULL,
    started_at  DATETIME(6)   NULL,
    finished_at DATETIME(6)   NULL,
    lease_until DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_job_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE,
    INDEX idx_job_status (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE watch_progress
(
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    user_id          BIGINT      NOT NULL,
    video_id         BIGINT      NOT NULL,
    position_seconds INT         NOT NULL,
    finished         BIT         NOT NULL DEFAULT 0,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_watch_progress UNIQUE (user_id, video_id),
    CONSTRAINT fk_progress_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_progress_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE watch_list_entry
(
    id       BIGINT      NOT NULL AUTO_INCREMENT,
    user_id  BIGINT      NOT NULL,
    video_id BIGINT      NOT NULL,
    added_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_watch_list UNIQUE (user_id, video_id),
    CONSTRAINT fk_watch_list_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_watch_list_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE settings
(
    id                  BIGINT       NOT NULL,
    welcome_sv          TEXT         NULL,
    welcome_en          TEXT         NULL,
    max_upload_gb       INT          NOT NULL,
    keep_originals      BIT          NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    last_modified_by    VARCHAR(255) NULL,
    last_modified_at    DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO settings (id, max_upload_gb, keep_originals)
VALUES (1, 50, 1);

CREATE TABLE audit_entry
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    at            DATETIME(6)   NOT NULL,
    actor_subject VARCHAR(64)   NOT NULL,
    actor_name    VARCHAR(255)  NULL,
    action        VARCHAR(40)   NOT NULL,
    entity_type   VARCHAR(20)   NOT NULL,
    entity_id     BIGINT        NULL,
    summary       VARCHAR(1000) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_audit_entry_at (at),
    INDEX idx_audit_entry_actor (actor_subject, at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE upload_session
(
    id            CHAR(36)     NOT NULL,
    owner         VARCHAR(64)  NOT NULL,
    length        BIGINT       NOT NULL,
    upload_offset BIGINT       NOT NULL,
    file_name     VARCHAR(255) NULL,
    created_at    DATETIME(6)  NOT NULL,
    completed_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_upload_session_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
