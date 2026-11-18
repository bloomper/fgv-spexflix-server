-- The start page's featured video, picked by an admin (else the newest show), and whether casting is offered

ALTER TABLE settings
    ADD COLUMN featured_video_uuid CHAR(36) NULL AFTER welcome_en,
    ADD COLUMN casting_enabled     BIT      NOT NULL DEFAULT 1 AFTER keep_originals;
