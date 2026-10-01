CREATE TABLE schema_info (version INTEGER NOT NULL);
INSERT INTO schema_info VALUES (1);
CREATE TABLE scans (
    scan_id BIGINT PRIMARY KEY,
    name VARCHAR NOT NULL UNIQUE,
    root VARCHAR NOT NULL,
    phase VARCHAR NOT NULL,
    algorithm VARCHAR NOT NULL DEFAULT 'SHA-256',
    active_dir BIGINT,
    next_entry_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT current_timestamp,
    updated_at TIMESTAMP NOT NULL DEFAULT current_timestamp
);
-- No indexes on the large append/analytical tables. IDs are application-assigned,
-- monotonically increasing within each scan, NOT filesystem inode numbers.
CREATE TABLE entries (
    scan_id BIGINT NOT NULL,
    entry_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL,
    relative_path VARCHAR NOT NULL,
    filename VARCHAR NOT NULL,
    kind VARCHAR NOT NULL,
    size BIGINT NOT NULL,
    modified_sec BIGINT NOT NULL,
    modified_nano INTEGER NOT NULL
);
CREATE TABLE directories (
    scan_id BIGINT NOT NULL,
    entry_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL,
    relative_path VARCHAR NOT NULL,
    completed BOOLEAN NOT NULL,
    PRIMARY KEY (scan_id, entry_id)
);
-- Only completed hashes exist. There is no pending/running/failed hash state.
CREATE TABLE hashes (
    scan_id BIGINT NOT NULL,
    entry_id BIGINT NOT NULL,
    sha256 VARCHAR NOT NULL
);
CREATE TABLE scan_errors (
    scan_id BIGINT NOT NULL,
    phase VARCHAR NOT NULL,
    relative_path VARCHAR NOT NULL,
    message VARCHAR NOT NULL,
    recorded_at_ms BIGINT NOT NULL
);
