ALTER TABLE artists ADD COLUMN canonical_artist_id INTEGER
    REFERENCES artists(id) ON DELETE SET NULL
    CHECK (canonical_artist_id IS NULL OR canonical_artist_id <> id);

CREATE INDEX idx_artists_canonical ON artists(canonical_artist_id);
