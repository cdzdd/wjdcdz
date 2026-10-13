CREATE TABLE clearing_runs (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 type VARCHAR(40) NOT NULL,
 business_date DATE NOT NULL,
 parameters JSON NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 active_scope CHAR(64) NULL,
 actor_id BIGINT NULL,
 business_time DATETIME(6) NOT NULL,
 state VARCHAR(32) NOT NULL,
 processed INT NOT NULL DEFAULT 0,
 waiting INT NOT NULL DEFAULT 0,
 failed INT NOT NULL DEFAULT 0,
 reason TEXT NOT NULL,
 result JSON NULL,
 created_at DATETIME(6) NOT NULL,
 started_at DATETIME(6) NULL,
 finished_at DATETIME(6) NULL,
 UNIQUE KEY uk_clearing_active_scope(active_scope),
 KEY ix_clearing_queue(state,id)
);
CREATE TABLE clearing_requests (
 actor_key BIGINT NOT NULL,
 request_key VARCHAR(200) NOT NULL,
 run_id BIGINT NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 PRIMARY KEY(actor_key,request_key),
 FOREIGN KEY(run_id) REFERENCES clearing_runs(id)
);
CREATE TABLE clearing_run_nodes (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 run_id BIGINT NOT NULL,
 node_order INT NOT NULL,
 name VARCHAR(40) NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
 processed INT NOT NULL DEFAULT 0,
 waiting INT NOT NULL DEFAULT 0,
 failed INT NOT NULL DEFAULT 0,
 reason TEXT NOT NULL,
 result JSON NULL,
 started_at DATETIME(6) NULL,
 finished_at DATETIME(6) NULL,
 UNIQUE KEY uk_clearing_node(run_id,name),
 FOREIGN KEY(run_id) REFERENCES clearing_runs(id)
);
