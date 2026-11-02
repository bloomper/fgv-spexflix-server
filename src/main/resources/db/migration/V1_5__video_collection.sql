-- Collections group videos in an order of their own, e.g. a spex's shows over the years. A video can be in several.
CREATE TABLE video_collection
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)      NOT NULL,
    title            VARCHAR(255)  NOT NULL,
    description      VARCHAR(4000) NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_by       VARCHAR(255)  NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    last_modified_by VARCHAR(255)  NULL,
    last_modified_at DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_video_collection_uuid UNIQUE (uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE video_collection_video
(
    collection_id BIGINT NOT NULL,
    video_id      BIGINT NOT NULL,
    position      INT    NOT NULL,
    PRIMARY KEY (collection_id, position),
    CONSTRAINT uk_video_collection_video UNIQUE (collection_id, video_id),
    CONSTRAINT fk_video_collection_video_collection FOREIGN KEY (collection_id) REFERENCES video_collection (id) ON DELETE CASCADE,
    CONSTRAINT fk_video_collection_video_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE,
    INDEX idx_video_collection_video_video (video_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
