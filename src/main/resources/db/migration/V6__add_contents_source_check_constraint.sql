ALTER TABLE contents
    ADD CONSTRAINT ck_contents_source
        CHECK (source IS NULL OR source IN ('TMDB', 'SPORTS_DB', 'MANUAL')) NOT VALID;