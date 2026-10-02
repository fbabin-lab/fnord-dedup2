-- Additive, independently versioned archive feature. Base filesystem schema stays at v1.
CREATE TABLE archive_schema_info (version INTEGER NOT NULL, instance_id VARCHAR NOT NULL);
CREATE TABLE archive_runs (scan_id BIGINT PRIMARY KEY, phase VARCHAR NOT NULL, updated_at TIMESTAMP DEFAULT current_timestamp);
CREATE TABLE archive_inputs (scan_id BIGINT, group_key VARCHAR, flavor VARCHAR, slot INTEGER, source_id BIGINT, relative_path VARCHAR, size BIGINT, modified_sec BIGINT, modified_nano INTEGER);
CREATE INDEX archive_input_group ON archive_inputs(scan_id,group_key);
CREATE TABLE archive_jobs (scan_id BIGINT, group_key VARCHAR, first_entry BIGINT, flavor VARCHAR, status VARCHAR NOT NULL, result_id VARCHAR, duplicate BOOLEAN DEFAULT false, retryable BOOLEAN DEFAULT false, diagnostic VARCHAR, PRIMARY KEY(scan_id,group_key));
-- A result is one immutable published attempt. RUNNING attempts are never authoritative.
-- Finalized child results survive interrupted parents and may be reused on replay.
CREATE TABLE archive_results (result_id VARCHAR PRIMARY KEY, scan_id BIGINT NOT NULL, source_label VARCHAR, fingerprint VARCHAR, policy VARCHAR, provider VARCHAR, state VARCHAR NOT NULL, retryable BOOLEAN NOT NULL DEFAULT false, reusable BOOLEAN NOT NULL DEFAULT false, height INTEGER NOT NULL DEFAULT 0, summary_json VARCHAR, started_at TIMESTAMP DEFAULT current_timestamp, completed_at TIMESTAMP);
CREATE INDEX archive_result_fingerprint ON archive_results(fingerprint);
CREATE TABLE archive_volumes (result_id VARCHAR, ordinal INTEGER, slot INTEGER, source_id BIGINT, source_path VARCHAR, size BIGINT, sha256 VARCHAR);
CREATE TABLE archive_members (result_id VARCHAR, ordinal BIGINT, relative_path VARCHAR, filename VARCHAR, kind VARCHAR, declared_size BIGINT, actual_size BIGINT, modified_sec BIGINT, modified_nano INTEGER, sha256 VARCHAR, recovered_sha256 VARCHAR, integrity VARCHAR, encrypted BOOLEAN, raw_path_base64 VARCHAR, diagnostic VARCHAR, group_key VARCHAR, flavor VARCHAR, volume_slot INTEGER);
CREATE TABLE archive_nested (parent_result_id VARCHAR, group_key VARCHAR, source_ordinal BIGINT, child_result_id VARCHAR, duplicate BOOLEAN);
CREATE TABLE archive_errors (result_id VARCHAR, ordinal BIGINT, category VARCHAR, code VARCHAR, message VARCHAR);
CREATE TABLE archive_temp_roots (path VARCHAR PRIMARY KEY, owner VARCHAR NOT NULL);
