"""Create invite_code table on the production database."""
import pymysql

conn = pymysql.connect(
    host="127.0.0.1",
    user="root",
    password="1234",
    db="couple_translator",
    charset="utf8mb4",
)
cursor = conn.cursor()

sql = """
CREATE TABLE IF NOT EXISTS invite_code (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    code VARCHAR(20) NOT NULL,
    is_used BOOLEAN NOT NULL DEFAULT FALSE,
    used_by BIGINT,
    used_at DATETIME,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX ix_invite_code_code (code),
    INDEX ix_invite_code_user_id (user_id),
    INDEX ix_invite_code_is_used (is_used)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"""

cursor.execute(sql)
conn.commit()
print("Table invite_code created successfully")

# Verify
cursor.execute("SHOW TABLES LIKE 'invite_code'")
result = cursor.fetchone()
print("Verified:", result)

conn.close()
