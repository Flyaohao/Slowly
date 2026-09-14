from dotenv import load_dotenv
import os

load_dotenv()

DB_URL: str = os.getenv("DB_URL", "mysql+pymysql://root:123456@localhost:3306/couple_translator?charset=utf8mb4")
DB_CHARSET: str = os.getenv("DB_CHARSET", "utf8mb4")
JWT_SECRET: str = os.getenv("JWT_SECRET", "change-me-in-production")

AI_API_KEY: str = os.getenv("AI_API_KEY", "")
AI_MODEL: str = os.getenv("AI_MODEL", "qwen-plus")
#: 记忆抽取等后台轻量任务专用模型。主模型若为推理模型，这类简单分类任务
#: 用它会明显偏慢偏贵（实测单次 ~10s vs 轻量模型 ~0.4s，判断结果一致）。
AI_MEMORY_MODEL: str = os.getenv("AI_MEMORY_MODEL", "qwen-turbo")
AI_BASE_URL: str = os.getenv("AI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")

# 向量库持久化目录。留空表示用默认的 backend/data/chroma；
# 容器部署时通过该变量把目录指到挂载卷上，避免重建镜像丢向量。
CHROMA_DIR: str = os.getenv("CHROMA_DIR", "")
