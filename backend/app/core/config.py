from dotenv import load_dotenv
import os

load_dotenv()

DB_URL: str = os.getenv("DB_URL", "mysql+pymysql://root:123456@localhost:3306/couple_translator?charset=utf8mb4")
DB_CHARSET: str = os.getenv("DB_CHARSET", "utf8mb4")
JWT_SECRET: str = os.getenv("JWT_SECRET", "change-me-in-production")

AI_API_KEY: str = os.getenv("AI_API_KEY", "")
AI_MODEL: str = os.getenv("AI_MODEL", "qwen-plus")
AI_BASE_URL: str = os.getenv("AI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")

# 向量库持久化目录。留空表示用默认的 backend/data/chroma；
# 容器部署时通过该变量把目录指到挂载卷上，避免重建镜像丢向量。
CHROMA_DIR: str = os.getenv("CHROMA_DIR", "")
