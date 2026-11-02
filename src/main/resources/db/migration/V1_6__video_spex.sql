-- A video can be about several spex, e.g. a jubilee show, or none. The first by position is its main spex: the poster,
-- the default title and year, and the category come from it. Each spex keeps the id of its original when it is a
-- revival, so that revivals can be shown together with their original.
CREATE TABLE video_spex
(
    video_id      BIGINT       NOT NULL,
    position      INT          NOT NULL,
    spex_id       CHAR(36)     NOT NULL,
    parent_id     CHAR(36)     NULL,
    title         VARCHAR(255) NULL,
    year          SMALLINT     NULL,
    revival       BIT          NULL,
    category_id   CHAR(36)     NULL,
    category_name VARCHAR(255) NULL,
    refreshed_at  DATETIME(6)  NULL,
    PRIMARY KEY (video_id, position),
    CONSTRAINT fk_video_spex_video FOREIGN KEY (video_id) REFERENCES video (id) ON DELETE CASCADE,
    INDEX idx_video_spex_spex (spex_id),
    INDEX idx_video_spex_parent (parent_id),
    INDEX idx_video_spex_category (category_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- The original of a revival is not known yet; the next SPEX_REFRESH fills it in.
INSERT INTO video_spex (video_id, position, spex_id, title, year, revival, category_id, category_name, refreshed_at)
SELECT id, 0, spex_id, spex_title, spex_year, spex_revival, spex_category_id, spex_category_name, spex_refreshed_at
FROM video
WHERE spex_id IS NOT NULL;

ALTER TABLE video
    DROP INDEX idx_video_spex,
    DROP COLUMN spex_id,
    DROP COLUMN spex_title,
    DROP COLUMN spex_year,
    DROP COLUMN spex_revival,
    DROP COLUMN spex_category_id,
    DROP COLUMN spex_category_name,
    DROP COLUMN spex_refreshed_at;
