CREATE TABLE artists (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL UNIQUE,
    sort_name TEXT,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE albums (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    artist_id INTEGER NOT NULL REFERENCES artists(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    normalized_title TEXT NOT NULL,
    release_year INTEGER,
    status TEXT NOT NULL DEFAULT 'CHECKED',
    relative_path TEXT,
    collection_id TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (artist_id, normalized_title, release_year)
);

CREATE INDEX idx_albums_artist_id ON albums(artist_id);
CREATE INDEX idx_albums_status ON albums(status);
CREATE INDEX idx_albums_collection_id ON albums(collection_id);

CREATE TABLE collections (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    parser TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    last_scan_at TEXT,
    last_scan_status TEXT,
    last_scan_message TEXT
);

CREATE TABLE scan_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collection_id TEXT,
    started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TEXT,
    status TEXT NOT NULL,
    parsed_count INTEGER NOT NULL DEFAULT 0,
    created_count INTEGER NOT NULL DEFAULT 0,
    updated_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    message TEXT
);

CREATE TABLE scan_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scan_run_id INTEGER NOT NULL REFERENCES scan_runs(id) ON DELETE CASCADE,
    level TEXT NOT NULL,
    message TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
