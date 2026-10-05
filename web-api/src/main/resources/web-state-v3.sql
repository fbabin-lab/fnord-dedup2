CREATE TABLE signatures (
    id VARCHAR PRIMARY KEY,
    algorithm VARCHAR NOT NULL CHECK (algorithm='SHA-256'),
    size BIGINT NOT NULL CHECK (size>=0),
    sha256 VARCHAR NOT NULL,
    tag VARCHAR NOT NULL,
    memo VARCHAR NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(algorithm,size,sha256)
);
