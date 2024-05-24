CREATE TABLE IF NOT EXISTS RetentionModel (
    id INTEGER PRIMARY KEY AUTO_INCREMENT,
    name TEXT NOT NULL,
    ownership TEXT NOT NULL,
    description TEXT,
    retention_period INTEGER NOT NULL,
    sensitive_fields TEXT,
    created_by TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_by TEXT NULL DEFAULT NULL,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    updated_to_id INTEGER DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS RetentionPolicy (
    id INTEGER PRIMARY KEY AUTO_INCREMENT,
    retention_model_id INTEGER NOT NULL,
    retention_period INTEGER,
    action TEXT NOT NULL,
    tenant TEXT NOT NULL,
    created_by TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_by TEXT NULL DEFAULT NULL,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    updated_to_id INTEGER DEFAULT NULL,
    FOREIGN KEY (retention_model_id) REFERENCES RetentionModel(id)
);
