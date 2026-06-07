CREATE TABLE artists (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    sort_name TEXT,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (normalized_name)
);

CREATE TABLE albums (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    normalized_title TEXT NOT NULL,
    release_date TEXT,
    sort_name TEXT,
    normalized_sort_name TEXT,
    sort_name_source TEXT NOT NULL DEFAULT 'AUTO',
    checked INTEGER NOT NULL DEFAULT 0,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (checked IN (0, 1)),
    CHECK (sort_name_source IN ('AUTO', 'MANUAL'))
);

CREATE INDEX idx_albums_checked ON albums(checked);
CREATE INDEX idx_albums_release_date ON albums(release_date);
CREATE INDEX idx_albums_title_release_date ON albums(normalized_title, release_date);
CREATE INDEX idx_albums_sort_name ON albums(normalized_sort_name);

CREATE TABLE album_artists (
    album_id INTEGER NOT NULL,
    artist_id INTEGER NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (album_id, artist_id),
    FOREIGN KEY (album_id) REFERENCES albums(id) ON DELETE CASCADE,
    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE
);

CREATE INDEX idx_album_artists_artist ON album_artists(artist_id);

CREATE TABLE collections (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'ARTIST',
    parser TEXT NOT NULL,
    last_scan_at TEXT,
    last_scan_status TEXT,
    last_scan_message TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (relative_path),
    CHECK (type IN ('ARTIST', 'TITLE'))
);

CREATE TABLE artist_collections (
    artist_id INTEGER NOT NULL,
    collection_id TEXT NOT NULL,
    local INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (artist_id, collection_id),
    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE,
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE,
    CHECK (local IN (0, 1))
);

CREATE INDEX idx_artist_collections_collection ON artist_collections(collection_id);

CREATE TABLE collection_albums (
    collection_id TEXT NOT NULL,
    album_id INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (collection_id, album_id),
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE,
    FOREIGN KEY (album_id) REFERENCES albums(id) ON DELETE CASCADE
);

CREATE INDEX idx_collection_albums_album ON collection_albums(album_id);

CREATE TABLE album_local_paths (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    album_id INTEGER NOT NULL,
    collection_id TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    first_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    missing_since TEXT,
    FOREIGN KEY (album_id) REFERENCES albums(id) ON DELETE CASCADE,
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE,
    UNIQUE (collection_id, relative_path)
);

CREATE INDEX idx_album_local_paths_album ON album_local_paths(album_id);
CREATE INDEX idx_album_local_paths_collection ON album_local_paths(collection_id);
CREATE INDEX idx_album_local_paths_missing ON album_local_paths(missing_since);

CREATE TABLE user_preferences (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE artist_provider_links (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    artist_id INTEGER NOT NULL,
    provider_id TEXT NOT NULL,
    provider_url TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    last_checked_at TEXT,
    last_success_at TEXT,
    last_error_at TEXT,
    last_error_message TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE,
    CHECK (enabled IN (0, 1)),
    UNIQUE (provider_id, provider_url)
);

CREATE INDEX idx_artist_provider_links_artist ON artist_provider_links(artist_id);
CREATE INDEX idx_artist_provider_links_enabled ON artist_provider_links(enabled);

CREATE TABLE scan_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collection_id TEXT,
    started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TEXT,
    status TEXT NOT NULL,
    parsed_count INTEGER NOT NULL DEFAULT 0,
    created_count INTEGER NOT NULL DEFAULT 0,
    updated_count INTEGER NOT NULL DEFAULT 0,
    missing_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    message TEXT,
    report_path TEXT,
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE SET NULL,
    CHECK (status IN ('RUNNING', 'DONE', 'FAILED', 'SKIPPED'))
);

CREATE TABLE scan_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scan_run_id INTEGER NOT NULL,
    level TEXT NOT NULL,
    message TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (scan_run_id) REFERENCES scan_runs(id) ON DELETE CASCADE,
    CHECK (level IN ('INFO', 'WARN', 'ERROR', 'SKIPPED'))
);

CREATE TABLE provider_check_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    artist_id INTEGER,
    provider_link_id INTEGER,
    started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TEXT,
    status TEXT NOT NULL,
    processed_artist_count INTEGER NOT NULL DEFAULT 0,
    found_album_count INTEGER NOT NULL DEFAULT 0,
    new_album_count INTEGER NOT NULL DEFAULT 0,
    existing_album_count INTEGER NOT NULL DEFAULT 0,
    error_count INTEGER NOT NULL DEFAULT 0,
    message TEXT,
    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE SET NULL,
    FOREIGN KEY (provider_link_id) REFERENCES artist_provider_links(id) ON DELETE SET NULL,
    CHECK (status IN ('RUNNING', 'DONE', 'FAILED', 'SKIPPED'))
);

CREATE TABLE provider_check_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL,
    artist_id INTEGER,
    provider_link_id INTEGER,
    level TEXT NOT NULL,
    message TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (run_id) REFERENCES provider_check_runs(id) ON DELETE CASCADE,
    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE SET NULL,
    FOREIGN KEY (provider_link_id) REFERENCES artist_provider_links(id) ON DELETE SET NULL,
    CHECK (level IN ('INFO', 'WARN', 'ERROR'))
);
