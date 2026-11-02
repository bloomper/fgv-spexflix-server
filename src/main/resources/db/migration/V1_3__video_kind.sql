-- Video kinds are configured by admins instead of being fixed. The first by position is the default for new videos.
CREATE TABLE video_kind
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    name_sv          VARCHAR(100) NOT NULL,
    name_en          VARCHAR(100) NOT NULL,
    position         INT          NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_by       VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    last_modified_by VARCHAR(255) NULL,
    last_modified_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_video_kind_uuid UNIQUE (uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO video_kind (uuid, name_sv, name_en, position, created_by, created_at)
VALUES ('00000000-0000-7000-8000-00000000b001', 'Föreställning', 'Show', 1, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000b002', 'Nummer', 'Number', 2, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000b003', 'Trailer', 'Trailer', 3, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000b004', 'Bakom kulisserna', 'Behind the scenes', 4, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000b005', 'Övrigt', 'Other', 5, 'system', NOW(6));

ALTER TABLE video
    ADD COLUMN kind_id BIGINT NULL AFTER kind;
UPDATE video v JOIN video_kind k ON k.position = CASE v.kind
                                                     WHEN 'NUMBER' THEN 2
                                                     WHEN 'TRAILER' THEN 3
                                                     WHEN 'BEHIND_THE_SCENES' THEN 4
                                                     WHEN 'OTHER' THEN 5
                                                     ELSE 1 END
SET v.kind_id = k.id;
ALTER TABLE video
    MODIFY kind_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_video_kind FOREIGN KEY (kind_id) REFERENCES video_kind (id),
    DROP COLUMN kind;
