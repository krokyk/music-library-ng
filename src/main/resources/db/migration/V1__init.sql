CREATE TABLE artists (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    sort_name TEXT,
    country_override TEXT,
    active_override INTEGER,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (active_override IN (0, 1)),
    UNIQUE (normalized_name)
);

CREATE TABLE collections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    type TEXT NOT NULL,
    last_scan_at TEXT,
    last_scan_status TEXT,
    last_scan_message TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (relative_path),
    CHECK (type IN ('ARTIST', 'TITLE'))
);

CREATE TABLE albums (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    collection_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    normalized_title TEXT NOT NULL,
    local_relative_path TEXT,
    release_year INTEGER,
    sort_name TEXT,
    normalized_sort_name TEXT,
    sort_name_source TEXT NOT NULL DEFAULT 'AUTO',
    checked INTEGER NOT NULL DEFAULT 0,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE,
    CHECK (checked IN (0, 1)),
    CHECK (sort_name_source IN ('AUTO', 'MANUAL')),
    CHECK (release_year IS NULL OR release_year BETWEEN 1000 AND 9999),
    UNIQUE (collection_id, local_relative_path)
);

CREATE INDEX idx_albums_collection ON albums(collection_id);
CREATE INDEX idx_albums_checked ON albums(checked);
CREATE INDEX idx_albums_release_year ON albums(release_year);
CREATE INDEX idx_albums_title_release_year ON albums(normalized_title, release_year);
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

CREATE TABLE user_preferences (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE providers (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

INSERT INTO providers (id, name)
VALUES
    ('musicbrainz', 'MusicBrainz'),
    ('spirit_of_metal', 'Spirit of Metal'),
    ('metal_archives', 'Metal Archives');

CREATE TABLE artist_provider_links (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    artist_id INTEGER NOT NULL,
    provider_id TEXT NOT NULL,
    provider_artist_id TEXT,
    provider_artist_name TEXT,
    country TEXT,
    disambiguation TEXT,
    active INTEGER,
    provider_url TEXT,
    enabled INTEGER NOT NULL DEFAULT 1,
    last_success_at TEXT,
    last_error_at TEXT,
    last_error_message TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE,
    FOREIGN KEY (provider_id) REFERENCES providers(id),
    CHECK (enabled IN (0, 1)),
    CHECK (active IN (0, 1)),
    UNIQUE (artist_id, provider_id),
    UNIQUE (provider_id, provider_artist_id)
);

CREATE INDEX idx_artist_provider_links_enabled ON artist_provider_links(enabled);

CREATE TABLE album_provider_links (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    album_id INTEGER NOT NULL,
    provider_id TEXT NOT NULL,
    provider_release_group_id TEXT NOT NULL,
    provider_title TEXT NOT NULL,
    provider_release_year INTEGER,
    provider_url TEXT,
    release_year_resolution TEXT,
    title_resolution TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (album_id) REFERENCES albums(id) ON DELETE CASCADE,
    CHECK (provider_release_year IS NULL OR provider_release_year BETWEEN 1000 AND 9999),
    CHECK (release_year_resolution IN ('KEEP_LOCAL', 'USE_PROVIDER', 'USE_OTHER_PROVIDER')),
    CHECK (title_resolution IN ('KEEP_LOCAL', 'USE_PROVIDER', 'USE_OTHER_PROVIDER')),
    UNIQUE (provider_id, provider_release_group_id),
    UNIQUE (album_id, provider_id, provider_release_group_id)
);

CREATE INDEX idx_album_provider_links_album ON album_provider_links(album_id);
