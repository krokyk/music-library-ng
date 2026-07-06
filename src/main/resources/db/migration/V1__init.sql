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
    last_local_scan_error_at TEXT,
    last_local_scan_error_message TEXT,
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
    FOREIGN KEY (album_id) REFERENCES albums(id) ON DELETE CASCADE,
    FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE,
    UNIQUE (collection_id, relative_path)
);

CREATE INDEX idx_album_local_paths_album ON album_local_paths(album_id);
CREATE INDEX idx_album_local_paths_collection ON album_local_paths(collection_id);

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
    provider_release_date TEXT,
    provider_url TEXT,
    release_date_resolution TEXT,
    title_resolution TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (album_id) REFERENCES albums(id) ON DELETE CASCADE,
    CHECK (release_date_resolution IN ('KEEP_LOCAL', 'USE_PROVIDER')),
    CHECK (title_resolution IN ('KEEP_LOCAL', 'USE_PROVIDER', 'USE_OTHER_PROVIDER')),
    UNIQUE (provider_id, provider_release_group_id),
    UNIQUE (album_id, provider_id, provider_release_group_id)
);

CREATE INDEX idx_album_provider_links_album ON album_provider_links(album_id);
