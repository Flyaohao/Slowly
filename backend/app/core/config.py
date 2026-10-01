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

# ---- 内测期全局兜底 AI 配置（2026-10-01）----
# 背景：v5.0 是 BYOK（用户必须自己配 api-key 才能用 AI）。内测阶段为了
# 让「没配 key」的人也能体验，未配置时**回落**到下面这套全局配置；
# 用户一旦自己配过，永远优先用用户自己的 —— BYOK 语义不变，兜底只是补位。
# 默认值全部继承 AI_API_KEY / AI_BASE_URL / AI_MODEL，所以**不改 .env 即可生效**；
# 想单独指定（例如兜底用更便宜的模型、或和后台任务分开计费）再显式设置。
#: 总开关。置 0 回到「未配置即 30010」的纯 BYOK 行为
AI_FALLBACK_ENABLED: bool = os.getenv("AI_FALLBACK_ENABLED", "1") == "1"
AI_FALLBACK_API_KEY: str = os.getenv("AI_FALLBACK_API_KEY", "") or AI_API_KEY
AI_FALLBACK_BASE_URL: str = os.getenv("AI_FALLBACK_BASE_URL", "") or AI_BASE_URL
AI_FALLBACK_MODEL: str = os.getenv("AI_FALLBACK_MODEL", "") or AI_MODEL
#: openai = OpenAI 兼容协议；anthropic = Anthropic Messages 协议
AI_FALLBACK_PROVIDER: str = os.getenv("AI_FALLBACK_PROVIDER", "openai")
#: 兜底的 embedding。向量库固定 1024 维，必须用 text-embedding-v4
AI_FALLBACK_EMBEDDING_MODEL: str = os.getenv(
    "AI_FALLBACK_EMBEDDING_MODEL", "text-embedding-v4"
)
AI_FALLBACK_EMBEDDING_BASE_URL: str = os.getenv("AI_FALLBACK_EMBEDDING_BASE_URL", "")

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

# ---- 军师 AI 记忆系统 v3.2（实现契约附录 §6.2 feature flags）----
#: 四个开关全部为 0 时，行为必须与 v1 完全一致（附录 §7.4 平价门禁）。
#: ⚠️ compose 的 environment 是白名单转发，新变量必须写进 docker-compose.yml 才会进容器。
#: 双写：同一条 ai_memory 行同时写 legacy 列与 v3.2 断言列；chat 蒸馏改走
#: memory_pipeline_task（T1-T4），非 chat 源仍走旧流程并在 create_memory 内联推导。
MEMORY_ASSERTION_DUAL_WRITE: bool = os.getenv("MEMORY_ASSERTION_DUAL_WRITE", "0") == "1"
#: v3 读取路径开关。阶段 A 只做管道（写入侧不读它），读路径切换属 §8 ④-⑤ 灰度项；
#: 阶段 B/C 完成 shadow 验证前不得置 1。
MEMORY_ASSERTION_READ_V3: bool = os.getenv("MEMORY_ASSERTION_READ_V3", "0") == "1"
#: 置 1 后 pipeline T4 遇到 v3 字段不完整的候选 → 重试/failed，禁止降级写 legacy_pending。
MEMORY_ASSERTION_REQUIRE_COMPLETE: bool = os.getenv("MEMORY_ASSERTION_REQUIRE_COMPLETE", "0") == "1"
#: 进程内索引 Worker（T5 generation CAS）。仅 DUAL_WRITE=1 时有活可干；
#: DUAL_WRITE=0 时新行不会写 pending_upsert，worker 空转（文档化 no-op 组合）。
MEMORY_ASSERTION_INDEX_WORKER: bool = os.getenv("MEMORY_ASSERTION_INDEX_WORKER", "0") == "1"

#: 解绑后记忆保留期（天）。dissolve 时写 memory_purge_after = now + 本值，
#: 到期且无 legal_hold 由清理线程执行 purge（DB + Chroma）。
#: 保留期是给「反悔/数据导出/争议取证」留的窗口，不是软删除展示期——
#: dissolved 关系的记忆早已被 AI_RECALL 硬边界挡在召回之外。
MEMORY_PURGE_RETENTION_DAYS: int = int(os.getenv("MEMORY_PURGE_RETENTION_DAYS", "30"))

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
