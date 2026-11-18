-- Aliases of video kinds that the import recognises (Spexipedia's docs/content-import.md, 3), a kind for busk shows as
-- the archive has them, and the checksum of the file an import brought

ALTER TABLE video_kind
    ADD COLUMN aliases VARCHAR(2000) NOT NULL DEFAULT '' AFTER name_en;

UPDATE video_kind SET position = position + 1 WHERE position > 1;

INSERT INTO video_kind (uuid, name_sv, name_en, aliases, position, created_by, created_at)
VALUES ('00000000-0000-7000-8000-00000000b006', 'Buskföreställning', 'Busk show', 'busk\nbuskföreställning\nbusktrix', 2, 'system', NOW(6));

UPDATE video_kind SET aliases = 'föreställning\nshow\noriginalföreställning\nnyuppsättning\nföreställningen\nsmygpremiär' WHERE uuid = '00000000-0000-7000-8000-00000000b001';
UPDATE video_kind SET aliases = 'nummer\nnumber\nlåt' WHERE uuid = '00000000-0000-7000-8000-00000000b002';
UPDATE video_kind SET aliases = 'bakom kulisserna\nrepetition\nrephelg\ngenrep\ndansinstruktioner\ndanser' WHERE uuid = '00000000-0000-7000-8000-00000000b004';
UPDATE video_kind SET aliases = 'övrigt\nannat\nother' WHERE uuid = '00000000-0000-7000-8000-00000000b005';

ALTER TABLE video
    ADD COLUMN source_checksum CHAR(64) NULL AFTER original_name;
