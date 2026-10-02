-- Optional independently versioned feature. Base and archive schemas are unchanged.
CREATE TABLE image_schema_info (version INTEGER NOT NULL, instance_id VARCHAR NOT NULL);
CREATE TABLE image_runs (scan_id BIGINT PRIMARY KEY, phase VARCHAR NOT NULL, updated_at TIMESTAMP DEFAULT current_timestamp);
CREATE TABLE image_jobs (scan_id BIGINT, source_id BIGINT, relative_path VARCHAR, format VARCHAR, size BIGINT, modified_sec BIGINT, modified_nano BIGINT, state VARCHAR NOT NULL, result_id VARCHAR, duplicate BOOLEAN DEFAULT false, retryable BOOLEAN DEFAULT false, component_of BIGINT, diagnostic VARCHAR, PRIMARY KEY(scan_id,source_id));
CREATE TABLE image_results (result_id VARCHAR PRIMARY KEY, scan_id BIGINT, source_id BIGINT, state VARCHAR NOT NULL, fingerprint VARCHAR, signature VARCHAR, provider VARCHAR, policy VARCHAR, virtual_size BIGINT, reusable BOOLEAN DEFAULT false, retryable BOOLEAN DEFAULT false, summary_json VARCHAR, started_at TIMESTAMP DEFAULT current_timestamp, completed_at TIMESTAMP);
CREATE INDEX image_fingerprint ON image_results(fingerprint);
CREATE TABLE image_components (result_id VARCHAR, ordinal BIGINT, parent_ordinal BIGINT, role VARCHAR, format VARCHAR, source_id BIGINT, relative_path VARCHAR, size BIGINT, modified_sec BIGINT, modified_nano BIGINT, sha256 VARCHAR);
CREATE TABLE image_partitions (result_id VARCHAR, device VARCHAR, number BIGINT, start_bytes BIGINT, size_bytes BIGINT, table_type VARCHAR);
CREATE TABLE image_filesystems (result_id VARCHAR, filesystem_id BIGINT, device VARCHAR, type VARCHAR, uuid VARCHAR, label VARCHAR, size_bytes BIGINT, state VARCHAR);
CREATE TABLE image_entries (result_id VARCHAR, filesystem_id BIGINT, entry_id BIGINT, relative_path VARCHAR, filename VARCHAR, kind VARCHAR, size BIGINT, actual_size BIGINT, modified_sec BIGINT, modified_nano BIGINT, sha256 VARCHAR, integrity VARCHAR);
CREATE TABLE image_errors (result_id VARCHAR, filesystem_id BIGINT, entry_id BIGINT, category VARCHAR, code VARCHAR, message VARCHAR);
CREATE TABLE image_temp_roots (path VARCHAR PRIMARY KEY, owner VARCHAR NOT NULL);
