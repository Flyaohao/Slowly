"""Create diary_entry table on the production database."""
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
CREATE TABLE IF NOT EXISTS diary_entry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    mood VARCHAR(50),
    weather VARCHAR(50),
    is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX ix_diary_entry_user_id (user_id),
    INDEX ix_diary_entry_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"""

cursor.execute(sql)
conn.commit()
print("Table diary_entry created successfully")

# Verify
cursor.execute("SHOW TABLES LIKE 'diary_entry'")
result = cursor.fetchone()
print("Verified:", result)

conn.close()
