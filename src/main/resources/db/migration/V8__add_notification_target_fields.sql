ALTER TABLE notifications
    ADD COLUMN type VARCHAR(50),
    ADD COLUMN resource_id UUID,
    ADD COLUMN source_entity_id UUID;
