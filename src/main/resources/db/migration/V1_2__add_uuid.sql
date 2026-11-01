-- Spexregister's spex and categories, and Spexify's tracks, are referred to by their external ids. The numeric ids
-- cannot be mapped to them, so the links are cleared and set again by hand or from fresh sample data.
UPDATE video SET spex_id = NULL, spex_category_id = NULL;
ALTER TABLE video MODIFY spex_id CHAR(36) NULL, MODIFY spex_category_id CHAR(36) NULL;

UPDATE chapter SET spexify_track_id = NULL;
ALTER TABLE chapter MODIFY spexify_track_id CHAR(36) NULL;

ALTER TABLE audit_entry MODIFY entity_id VARCHAR(36) NULL;
UPDATE audit_entry e JOIN video v ON e.entity_type = 'VIDEO' AND e.entity_id = CAST(v.id AS CHAR) SET e.entity_id = v.uuid;
