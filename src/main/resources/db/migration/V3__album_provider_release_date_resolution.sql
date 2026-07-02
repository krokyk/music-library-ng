ALTER TABLE album_provider_links ADD COLUMN release_date_resolution TEXT CHECK (release_date_resolution IN ('KEEP_LOCAL', 'USE_PROVIDER'));
