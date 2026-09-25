from dotenv import load_dotenv
import os

load_dotenv()

DB_URL: str = os.getenv("DB_URL", "mysql+pymysql://root:123456@localhost:3306/couple_translator?charset=utf8mb4")
DB_CHARSET: str = os.getenv("DB_CHARSET", "utf8mb4")
JWT_SECRET: str = os.getenv("JWT_SECRET", "change-me-in-production")

AI_API_KEY: str = os.getenv("AI_API_KEY", "")
#: 主模型。默认值需与 .env.example 保持一致：这里只在 .env 缺失时兜底，
#: 而容器部署不带 .env（由 compose 注入环境变量），兜底值就是实际生效值。
AI_MODEL: str = os.getenv("AI_MODEL", "qwen3.7-flash")
#: 记忆抽取等后台轻量任务专用模型。主模型若为推理模型，这类简单分类任务
#: 用它会明显偏慢偏贵（实测单次 ~10s vs 轻量模型 ~0.4s，判断结果一致）。
AI_MEMORY_MODEL: str = os.getenv("AI_MEMORY_MODEL", "qwen-turbo")
AI_BASE_URL: str = os.getenv("AI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")

#: 推理模型的思考预算（token）。主模型是推理模型，**思考长度直接等于用户等待时间**：
#: 实测同一封信的解读，不限制时思考 9258 字 / 首字正文 32.9s；压到 1024 时
#: 思考 3343 字 / 首字 13.4s；完全关掉思考则 0.9s 出字。取 0 表示不限制。
AI_THINKING_BUDGET: int = int(os.getenv("AI_THINKING_BUDGET", "1024"))
#: 是否允许模型产出思考过程。置 false 后连思考帧都不再产生（前端「深度思考」
#: 面板会空着），换来最快的首字响应；一般只在演示「极速模式」时用。
AI_ENABLE_THINKING: bool = os.getenv("AI_ENABLE_THINKING", "true").lower() == "true"

# 向量库持久化目录。留空表示用默认的 backend/data/chroma；
# 容器部署时通过该变量把目录指到挂载卷上，避免重建镜像丢向量。
CHROMA_DIR: str = os.getenv("CHROMA_DIR", "")

# ---- 邮件（忘记密码验证码）----
SMTP_HOST: str = os.getenv("SMTP_HOST", "smtp.qq.com")
SMTP_PORT: int = int(os.getenv("SMTP_PORT", "465"))
#: 发信邮箱，如 1106665698@qq.com
SMTP_USER: str = os.getenv("SMTP_USER", "")
#: SMTP 授权码（QQ 邮箱：设置-账户-开启 SMTP 服务后生成授权码，不是 QQ 密码）
SMTP_AUTH_CODE: str = os.getenv("SMTP_AUTH_CODE", "")
EMAIL_FROM: str = os.getenv("EMAIL_FROM", SMTP_USER)
#: true 时验证码不真实发信：写库+打日志，forgot-password 返回 dev_code 便于联调
EMAIL_DEV_MODE: bool = os.getenv("EMAIL_DEV_MODE", "false").lower() == "true"

# ---- AI 安全护栏 ----
#: AI 端点按用户限流（slowapi 语法）。AI 调用是重资源操作（推理模型单次 ~30s），
#: 防刷同时也是成本保护。0 或空串表示关闭。
AI_RATE_LIMIT: str = os.getenv("AI_RATE_LIMIT", "20/hour")

#: AI 端点的 **IP 维度**兜底限流（slowapi 语法）。
#: 上面那条的限流键是 user_id，批量注册可让每个账号各享一份配额；
#: 这条按 IP 记总量，把它们兜住。默认 120/hour，正常用户碰不到。
AI_IP_RATE_LIMIT: str = os.getenv("AI_IP_RATE_LIMIT", "120/hour")

# ---- 接口文档与跨域 ----
#: /docs、/redoc、/openapi.json 的保护口令（HTTP Basic）。
#: openapi.json 会给出全部接口的参数与结构，等同一份攻击说明书，因此不对外敞开。
#: 两者任一为空时一律拒绝访问 —— 宁可自己进不去，也不默认开放。
#: ⚠️ compose 的 environment 是白名单转发，新变量必须写进 docker-compose.yml 才会进容器。
DOCS_USER: str = os.getenv("DOCS_USER", "")
DOCS_PASS: str = os.getenv("DOCS_PASS", "")

#: 允许跨站访问的来源，逗号分隔。默认空 = 不允许任何跨站来源。
#: Android 客户端走 Retrofit，不受 CORS 约束（那是浏览器机制）；/docs 的
#: Try it out 是同源请求。所以收紧此项对现有客户端零影响。
CORS_ORIGINS: str = os.getenv("CORS_ORIGINS", "")
