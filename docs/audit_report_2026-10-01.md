# Slowly / 慢慢说 — 后端架构与接口审计报告

**审计范围**：`D:/Mycode/couple/backend/app`（FastAPI，168 接口）
**审计方式**：只读静态审计（grep 文件内容，未遍历 `app.routes`）
**审计日期**：2026-10-01
**审计人**：高见远（架构师）

> 路由判读说明：FastAPI 0.141 + Starlette 1.6 下 `app.routes` 静态遍历看不到子路由，本报告全部路由结论基于对 `app/api/v1/**.py` 与 `*/router.py` 的文件内容 grep，不依赖运行时路由表。

---

## 一、CRUD 完整性矩阵

图例：`✅` 完备 | `⚠️` 有但有缺陷（见备注）| `❌ 缺失` | `—` 该资源不适用

| 资源 | 列表 | 详情 | 创建 | 更新 | 删除 | 缺失项 / 备注 |
|---|---|---|---|---|---|---|
| **users** | — | ✅ `GET /users/me` | ✅ 注册 `POST /auth/register` | ✅ `PUT /users/me` | ❌ **无注销/删号** | 无物理删除入口（✅ 符合审计偏好）；但**无账号注销**，GDPR/合规缺口 |
| **user_profile** | — | ✅ `GET /users/me`、`GET /profiles/me` | ✅ 问卷提交生成 | ✅ `PUT /users/me` | — | 昵称/性别无独立删除 |
| **user（通知偏好）** | — | ✅ `GET /users/me/notification-pref` | — | ✅ `PUT` | — | — |
| **user（私密密码）** | — | — | ✅ `POST /me/private-password` | ✅ `POST /me/private-verify` | ❌ 无解绑入口 | 只能覆盖不能关闭 |
| **user_ai_config** | ✅ key 列表 `GET /me/ai-config` | ✅ 同上 | ✅ `PUT /me/ai-config`、`POST /keys` | ✅ `PATCH /keys/{id}` | ✅ `DELETE /me/ai-config`、`DELETE /keys/{id}` | 全 CRUD 完整，唯一资源完整的用户子表 |
| **couple（关系）** | ❌ 无「关系列表」 | ✅ `GET /couples/me` | ✅ `POST /couples/invite`、`POST /couples/bind` | ✅ `PUT /couples/me/space` | ✅ `POST /unbind` + `/confirm` | 关系是单例，无需列表；解绑是**软状态机**（✅ 留痕） |
| **invite_code** | ❌ | ❌ 不可查 | ✅ `POST /couples/invite` | ❌ | ❌ 不可删 | 生成后只能等过期或被 bind 消耗，用户无法主动作废 |
| **questionnaire（量表）** | ✅ `GET /active` | ✅ `GET /{id}/questions` | ❌ 仅 seed，**无创建接口** | ❌ | ❌ | 量表是产品配置，运行时只读 → **可接受**（但缺运营手段，改题必须发版） |
| **questionnaire_submission** | ✅ `GET /history`（⚠️ **无分页**） | ✅ `GET /history/{id}` | ✅ `POST /{id}/submit` | ❌ **不可改交卷** | ✅ `DELETE /history/{id}` | 列表无分页，见 D-07 |
| **questionnaire_answer** | ✅ `GET /me/progress` | — | ✅ `POST /{id}/answers` | ✅ 同上（upsert） | ❌ 无清空 | 只能整体覆盖 |
| **personality_profile** | — | ✅ `GET /profiles/personality` | ✅ 问卷生成 | ❌ **无手动更新** | ❌ | MBTI/星座由问卷推导，无手改入口 → **可接受** |
| **profile（个人画像）** | ✅ `GET /profiles/history` | ✅ `GET /profiles/me` | ✅ 问卷 / `POST /me/enrich` | ✅ `POST /versions/{id}/restore` | ⚠️ 只能删版本，**不能删画像本体** | — |
| **profile_version** | ✅ `GET /profiles/versions` | ✅ `GET /versions/{id}`、`/diff` | ✅ `POST /versions` | ✅ `POST /versions/{id}/restore` | ✅ `DELETE /versions/{id}` | 本项目 CRUD 最完整的资源 |
| **couple_profile** | — | ✅ `GET /profiles/couple` | ✅ 问卷双方完成后生成 | ❌ | ❌ | 同上，派生只读 |
| **avatar** | ✅ `GET /avatars/assets`（⚠️ 已冻结 10006） | ✅ `GET /avatars/me` | ✅ `PUT /avatars/me` | ✅ `PUT /me` | ❌ 无解绑 | — |
| **avatar（头像文件）** | — | — | ✅ `POST /users/me/avatar` | ✅ 同上覆盖 | ❌ **旧头像文件永不删除** | 磁盘泄漏，见 D-12 |
| **anniversary** | ✅ `GET`（page/page_size，20/100） | ❌ **无单条详情** | ✅ `POST` | ✅ `PUT /{id}` | ✅ `DELETE /{id}` | 缺 R（详情）；列表用 `page/page_size` |
| **relationship_event** | ✅ `GET`（page/page_size） | ✅ `GET /{id}` | ✅ `POST` | ✅ `PUT /{id}` | ✅ `DELETE /{id}` | 全 CRUD 完整 |
| **letter** | ✅ `GET`（page/page_size，20/100）+ `/inbox` + `/drafts` | ✅ `GET /{id}` | ✅ `POST` | ✅ `PUT /{id}`（仅 draft 可改） | ✅ `DELETE /{id}` + `/batch-delete` | 全 CRUD 完整；软删 ✅ |
| **letter_analysis（解读）** | — | ✅ `GET /ai/generations/{kind}` | ✅ 同步 `/understand-letter` + 流式 | ❌ 不可改 | ❌ 不可删 | 覆盖式（同一 target 只留最新）→ 用户重跑即丢上次，见 D-14 |
| **letter_rewrite（改写）** | — | ✅ 同上 `kind=letter_rewrite` | ✅ | ❌ | ❌ | 同上 |
| **letter_reply（建议回信）** | — | ✅ 同上 `kind=letter_reply` | ✅ | ❌ | ❌ | 同上 |
| **mediation（旧会话）** | ✅ `GET /mediation` | ✅ `GET /{session_id}` | ✅ `POST /start` | ✅ `/accept` `/reject` `/input` `/confirm` | ❌ **无放弃/删除** | 状态机无终态「取消」，用户发起后无法退出 |
| **mediation_room** | ✅ `GET`（⚠️ 无分页） | ✅ `GET /{id}` | ✅ `POST` | ✅ `/agree` `/supplement` `/end` `/settlement/confirm` `/settlement/retry` | ❌ **无删除** | 无分页，见 D-08；房间是永久档案（设计如此） |
| **room_message** | ✅ `GET /{id}/messages`（after_id/limit 水位轮询） | — | ✅ `POST /{id}/messages` | ❌ **不可编辑/撤回** | ❌ **不可删除** | 微信群模型，设计如此；但**发错字无法撤回**是真实用户痛点 |
| **memory（军师记忆）** | ⚠️ `GET /ai/memory`（**无分页**） | ⚠️ 无单条详情 | ✅ 蒸馏自动 + `POST /viewpoint/{id}` | ✅ `/visibility` `/importance` | ✅ `DELETE /{id}` | 列表无分页且**可能全表返回**，见 D-06 |
| **memory_assertion** | — | — | ✅ 经 `link_viewpoint_memory` | ❌ | ✅ 随记忆级联 | 纯派生，无独立入口（✅ 合理） |
| **diary_entry（观点）** | ✅ `GET /single/diary`（**page/limit**，命名与其他资源不一致） | ✅ `GET /{id}` | ✅ `POST` | ✅ `PUT /{id}` | ✅ `DELETE /{id}` + `/batch-delete` | 全 CRUD 完整；软删 ✅ |
| **wishlist** | ✅ `GET`（page/page_size） | ❌ **无单条详情** | ✅ `POST` | ✅ `PUT /{id}` + `/complete` | ✅ `DELETE /{id}` | 缺 R（详情） |
| **museum** | ✅ `GET`（page/page_size） | ✅ `GET /{id}` | ✅ `POST` | ✅ `PUT /{id}` + `/pin` | ✅ `DELETE /{id}` | CRUD 完整，但**整模块冻结 10006**（`features.py:42`）→ 实际不可用 |
| **museum_image** | — | — | ✅ `POST /museum/upload-image` | ✅ 随 item 更新 | ❌ **孤儿文件永不清理** | 磁盘泄漏，见 D-12 |
| **presence** | ⚠️ `GET /presence/feed`（**已冻结**） | — | ⚠️ `POST /presence/moment`（**已冻结**） | — | — | 3 端点中 2 个冻结；`meet-date` 保留 |
| **dual_perspective** | ✅ `GET`（page/page_size） | ✅ `GET /{id}` | ✅ `POST` + `POST /{id}/records` | ✅ `PUT /{id}/records/{rid}` | ❌ **无删除** | 缺 D；`reveal` 是状态推进非删除 |
| **observation** | — | ✅ `GET /observation`（聚合单条） | ✅ 自动拼装 | ✅ `POST /ack` | — | 无 body 的写操作，幂等 ✅ |
| **home（聚合）** | — | ✅ `GET /home` | — | — | — | 纯读聚合，无分页概念 |
| **advisor_settings** | — | ✅ `GET /advisor/settings` | ✅ 同 PUT | ✅ `PUT /advisor/settings` | ✅ 回落默认（无显式删） | 空 PUT 幂等 ✅（`advisor.py:35`） |
| **ai_chat_session** | ⚠️ `GET /ai/sessions`（**无分页**） | ✅ `GET /sessions/{id}/messages` | ✅ 隐式（首句 chat） | ✅ `POST /sessions/{id}/close` | ✅ `DELETE /sessions/{id}` | 列表无分页，见 D-07 |
| **ai_generation** | ❌ 无列表接口 | ✅ `GET /generations/{kind}` | ✅ 隐式（prepare_*） | ❌ 不可改 | ❌ 不可删 | **缺 L**：用户无法查看「我一共生成过什么」，排障只能靠 DB |
| **ai_output_feedback** | ✅ `GET /ai/feedback/pending` | — | ✅ `POST /sessions/{id}/feedback` | ✅ 同上（upsert） | ❌ | 幂等 upsert ✅ 有唯一约束 |
| **relationship_review（复盘）** | ✅ `GET /ai/review/history`（page/page_size） | ✅ `GET /ai/review/{id}` | ✅ 流式 `/review/stream` | ✅ `POST /review/{id}/outcome` | ❌ **无删除** | 缺 D：负面复盘记录无法清除 |
| **ai_task（队列）** | — | — | ✅ 内部 | — | — | 无用户面接口（✅ 内部资源） |
| **mediation_event** | — | — | ✅ 结算时自动 | ❌ | ❌ | 派生记录，无入口（✅） |
| **unbinding_request** | — | — | ✅ `POST /unbind` | ✅ `/confirm` `/cancel` | ✅ 冷静期到期自动 | 状态机完整 ✅ |
| **email_verification** | — | — | ✅ 内部 | — | ✅ 过期清理 | — |

### 缺失动作的建议路径与方法

| 缺失 | 建议 | 理由 |
|---|---|---|
| `ai_generation` 列表 | `GET /api/v1/ai/generations?page=&page_size=` | 用户「我的 AI 记录」是排障与信任建立的基础；当前只能靠 `target_id` 猜 |
| anniversary / wishlist 详情 | `GET /anniversaries/{id}`、`GET /wishlists/{id}` | 列表已分页，客户端点进详情只能整页重拉 |
| 用户注销 | `DELETE /api/v1/users/me` | 合规硬要求；需连带解绑 + 记忆 purge（`memory_purge_service` 已具备能力） |
| 旧调解放弃 | `POST /mediation/{id}/cancel` | 状态机无终态，用户发起后被锁死 |
| room_message 撤回 | `DELETE /mediation-rooms/{rid}/messages/{mid}` | 发错字无法挽回，是高频真实诉求 |

---

## 二、P0 缺陷清单

### P0 级（会直接产生 500 / 50000 / 数据错误 / 架构红线违反）

---

**D-01｜P0｜两个同名 `diary_repo` 并存，`get_list` 返回类型不一致，改任一处就静默炸**
`backend/app/repositories/diary_repo.py:41`（活，返回 `List`）vs `backend/app/repositories/single/diary_repo.py:42`（死，返回 `Tuple[List, int]`）
触发场景：任何人把 `home_service.py:18` / `ai_service.py:2335` 的 `from app.repositories import diary_repo` 改成 `.single.diary_repo`，`home_service.py:61` 的 `diary_repo.get_recent(...)` 仍可用，但任何按 `get_list` 取值的地方会因解包数不符而 TypeError。**活文件并非 `single/` 那份**（`single/diary_repo.py` 的 `update` 多了一行 `if hasattr` 差异），两处行为已经漂移。
建议：删除 `app/repositories/diary_repo.py`，`home_service.py:18` 与 `ai_service.py:2335` 改指 `repositories.single.diary_repo`，`get_list` 调用方统一按元组解包。

---

**D-02｜P0｜`int(code)` 遇到非数字 ValueError 直接 ValueError→10000**
`backend/app/api/v1/couple/avatars.py:36`、`avatars.py:58`、`avatars.py:79`、`common/home.py:26`、`couple/letters.py:39`、`couple/museum.py:65`、`couple/wishlists.py:31`、`couple/dual_perspectives.py:32`（共 8+ 处 `int(code)` 无 try）
触发场景：任何 service 抛出非纯数字错误码（`memory_service.py:253` 抛 `"记忆不存在"`、`memory_service.py:246` 抛 `"importance 只能是 0 或 2"`、`ai_task_service.py:206` 抛中文串）而调用方没在白名单里命中，`int()` 自己抛 ValueError，被全局 handler 吞成 `10000 服务器内部错误`，用户看到的是「服务器内部错误」而不是真实原因。
建议：抽一个统一 `to_business_code(code, default)` 工具函数（内部 try/except），替换所有裸 `int(code)`。`profiles.py:29-36` 的 `_profile_error` 已经是对的写法，可作为模板。

---

**D-03｜P0｜LLM 调用发生在 API 进程，违反「LLM 只由 worker 跑」红线**
`backend/app/api/v1/common/questionnaires.py:168`（`analyze_for_user` → `questionnaire_analysis_service.py:126` `client.invoke_structured`）与 `common/profiles.py:194`（`generate_profile_report`）
触发场景：用户点「生成画像报告」，请求线程同步阻塞 10-30 秒。并发 10 个请求就打满 uvicorn worker，`backend` 容器被 AI 调用拖垮，同时 `ai-task-worker` 空闲。docker-compose 三服务分工形同虚设。
建议：这两个端点改为「投递 ai_task + 返回 task_id」，前端轮询或走 WS 拿结果；或至少迁到 worker 的独立 HTTP 入口。

---

**D-04｜P0｜`questionnaire_id` 路径参数被完全忽略，用户传什么 id 都分析同一份数据**
`backend/app/api/v1/common/questionnaires.py:168` — `analyze_for_user(db, current_user.id)` 签名里**没有** `questionnaire_id`
触发场景：前端传 `POST /questionnaires/1/analyze`，实际分析的是 `profile_repo.get_latest_profile`（最新一份画像）。若有 3 份问卷提交记录，用户点第 1 份的「分析」，看到的是第 3 份的结果。**这是契约漂移**：URL 承诺 per-questionnaire，实际是 per-user-latest。写库时 `questionnaires.py:189` 才用 `questionnaire_id` 过滤，逻辑自相矛盾。
建议：把 `questionnaire_id` 传进 `analyze_for_user`，用 `questionnaire_repo.get_submission_by_id` 定位到具体那份 submission；或在路径层直接去掉这个参数，改为 `POST /questionnaires/analyze`。

---

**D-05｜P0｜注册存在 TOCTOU 竞态，唯一约束冲突未捕获 IntegrityError**
`backend/app/services/auth_service.py:26-34`（先 `get_user_by_email` 查、后 `create_user` 插）+ `backend/app/models/user.py:10`（`email` unique）
触发场景：同一邮箱双击注册 / 两个设备同时注册。两次请求都通过 `existing` 检查，第二个 INSERT 触发 `IntegrityError(1062)`，**全链路无 `IntegrityError` 捕获**，被全局 handler 吞成 `10000 服务器内部错误`。用户看到「服务器内部错误」而不是「邮箱已注册」，且提示限流 3/min 让人无法重试。
建议：`register` 内改用 SAVEPOINT（`db.begin_nested()`）+ `except IntegrityError: raise ValueError("20001")`，参考 `ai_task_repo.py:156` 的既有写法。

---

**D-06｜P0｜记忆列表无分页 + 无 limit，数据量随使用线性增长**
`backend/app/services/memory_service.py:237-239` — `.order_by(...).all()` 无 `.limit()`
触发场景：用户用了 6 个月，AI 蒸馏积累 2000+ 条记忆。每次打开记忆页（含军师每轮 RAG 前的可见列表、首页聚合）全量加载。MySQL 单查询 2000 行 × TEXT 字段，接口耗时从 50ms 涨到数秒；`memory_retrieval.py:663` 另有一处无 limit `.all()`。
建议：加 `page/page_size`（默认 20/上限 100），`get_memories` 改返回 `(items, total)`。

---

**D-07｜P0｜三个列表接口零分页，且总数不等于元素数**
`backend/app/services/ai_service.py:1189`（`get_sessions` → `ai_repo.py:183` `.all()`）、`backend/app/api/v1/common/questionnaires.py:267-269`（`get_submission_history` 返回裸数组无 total 无分页）、`backend/app/services/mediation_room_service.py:71-77`（`list_rooms` 用 `len(rooms)` 当 total）
触发场景：AI 军师聊了 500 轮 → `GET /ai/sessions` 返回 500 条；问卷提交 50 次 → `/history` 返回 50 条裸数组（客户端无法做分页 UI）；调解室开 100 个房 → `list_rooms` 一次性拉全量再 `len()`。三处都会随时间劣化。
建议：统一改 `page/page_size` + 独立 `count()` 查询（不要用 `len(全量列表)` 当 total）。

---

**D-08｜P0｜`int(code)` 在 `letter_service` 抛 10006 时被绕过，冻结功能返回 500**
`backend/app/api/v1/couple/letters.py:37-39` — `if code == "10006": raise feature_disabled_error()` 才拦住；但 `museum.py:65` / `wishlists.py:31` 的 `int(code)` 路径没有 10006 分支
触发场景：`museum_service` 若在冻结检查之前抛出 10006（`require_feature` 是 router 级依赖，先于 handler 执行，所以当前侥幸不触发）—— 但 `wishlists` 开关是 `True`（`features.py:44`），一旦运营把它关掉，`anniversary_service` 任何 `ValueError` 走到 `int(code)` 就 500。
建议：与 D-02 一并修；`int()` 兜底 + 补 10006 白名单。

---

### P1 级（用户可撞上，但有替代路径或概率较低）

---

**D-09｜P1｜`created_at.isoformat()` 无 None 保护**
`backend/app/api/v1/common/profiles.py:60`、`:182`（`/history` 循环内）、`common/profiles.py:139`、`:147`、`:155`
触发场景：`TimestampMixin.created_at` 是 `server_default=func.now()` 且 `nullable=False`（`models/base.py:9`），正常不会 None。但 `profiles.py:139` 的 `cp.created_at`（couple_profile）若为历史脏数据或手工插入，`AttributeError: 'NoneType' object has no attribute 'isoformat'` → 10000。对比 `home_service.py:67` 写的是 `if d.created_at else None`，同一项目两种写法。
建议：全部改成 `x.created_at.isoformat() if x.created_at else None`。

---

**D-10｜P1｜`batch_delete_letters` N+1 查询 + 无批量上限**
`backend/app/services/letter_service.py:276-292`（循环内逐条 `get_letter_by_id`）+ `backend/app/schemas/letter_schema.py`（`BatchDeleteRequest.letter_ids` 无 `max_length`）
触发场景：用户选中 500 封信批量删除 → 500 次 SELECT + 500 次 UPDATE，每次 `soft_delete_letter` 都 `db.flush()` 但直到路由层 `letters.py:191` 才统一 `commit()`。500 条长事务持有行锁，同时 `ai_generation` 写入被阻塞。对比 `diary.py` 的 `batch_soft_delete` 用单条 `IN` + `UPDATE`（`single/diary_repo.py:111-122`），是正确写法。
建议：`BatchDeleteRequest` 加 `max_length=200`；service 改单条 `UPDATE ... WHERE id IN (...) AND (sender_id=:me OR receiver_id=:me)`。

---

**D-11｜P1｜`diary.py` 裸 `except Exception` 吞掉 `HTTPException`，错误码张冠李戴**
`backend/app/api/v1/single/diary.py:61-66`（`create_diary`）、`:142-147`（`update_diary`）、`:166-171`（`delete_diary`）
触发场景：`update_diary` 里第 135 行 `raise HTTPException(404)`，被第 140 行 `except HTTPException: raise` 正确重抛 ✅。但 `create_diary` **没有** `except HTTPException` 分支——它内部第 53 行 `write_diary_memory` 虽有独立 try，但若 `diary_repo.create` 抛出任何异常（含未来加的 HTTPException），统一变成 `code=60002「创建日记失败」`。`60002` 在信件模块语义是「无权访问」，这里被拿来表示「创建失败」，客户端按 code 分支会显示错文案。
建议：`create_diary` 补 `except HTTPException: raise`；错误码换成独立的 `6xxxx 创建失败` 段。

---

**D-12｜P1｜上传的孤儿文件永不清理**
`backend/app/api/v1/couple/museum.py:48-49`（写盘）、`backend/app/api/v1/common/users.py:61`（`user_service.upload_avatar` 覆盖写）
触发场景：用户上传 5MB 照片作为藏品配图，随后删除藏品（`DELETE /museum/{id}`）或换头像。磁盘文件永久残留。`uploads/museum/` 无清理任务，`main.py:188` 的 StaticFiles 挂载让这些文件**永久可公开访问**（无鉴权）。1000 用户 × 平均 2MB = 2GB 泄漏，且任何知道 URL 的人都能看到（UUID 难猜，但 `/uploads/` 目录无索引保护）。
建议：加定时清理任务（比对 `museum_item.image_url` / `user_profile.avatar_url` 删孤儿）；或改为按需鉴权下载。

---

**D-13｜P1｜`admin.py` 把内部异常原文回传给客户端**
`backend/app/api/v1/admin.py:39` — `detail={"code": 10000, "message": str(e), "data": None}`
触发场景：定时任务抛异常，`str(e)` 可能是含表名/连接串/SQL 片段的驱动错误，直接进 HTTP 响应。虽然有 `X-Admin-Token` 保护，但一旦口令泄漏即成为信息泄露通道。
建议：`message` 改为固定文案，真实原因只进日志。

---

**D-14｜P1｜`ai_generation` 覆盖式写入，用户重跑即永久丢失上次结果**
`backend/app/repositories/ai_generation_repo.py:37-58`（`upsert_generation` 按 `user_id+kind+target_type+target_id` 定位后**更新**）
触发场景：用户对同一封信点了 3 次「AI 帮我理解」，每次都覆盖上一条。想对比「两次解读有何不同」做不到。中断保存（`ai_generation_service.py:471`）也走同一 upsert，用户点「停止生成」再点「重新生成」，半成品被彻底覆盖。
建议：要么加 `attempt` 序号保留历史，要么在覆盖前把旧行 `status` 标为 `superseded` 保留。

---

**D-15｜P1｜`get_generation` 裸 `raise` 泄漏 ValueError 到全局 handler**
`backend/app/api/v1/couple/ai.py:530` — `except ValueError as e:` 只处理 `"60002"`，其余 `raise` 原样抛出
触发场景：`get_saved` 目前只抛 60002（`ai_generation_service.py:121`），但 `db.query(Letter).filter(...).first()` 若遇到 DB 层异常（如锁等待超时）会抛 `OperationalError` 而非 ValueError，走全局 `10000`。同时 `kind` 参数**无白名单校验**——`GET /ai/generations/任意字符串` 会走到 DB 查询，`target_type` 同样无校验。
建议：`kind` / `target_type` 加枚举白名单（`ai_generation_service.py:16-22` 已有 `GENERATION_KIND_*` 常量可复用）；`raise` 改为 `raise HTTPException(code=50000)`。

---

**D-16｜P1｜`questionnaires.py` 两处 500 掩盖了可诊断的错误**
`backend/app/api/v1/common/questionnaires.py:176-184`、`:226-229` — 未知 ValueError 一律 `HTTP_500` + `code=50003`
触发场景：用户没配 AI Key（`AiConfigMissingError`）时调 `/analyze`——`AiConfigMissingError` 是 `RuntimeError` 不是 `ValueError`，会走第 180 行的 `except Exception` → 50003「分析生成失败」，而正确出口应是全局 handler 的 30010「请先配置 AI」。用户在设置页配好了 Key 再回来，错误文案还是「分析生成失败」，无法自查。
建议：删掉 `except Exception` 那两段，交给全局 handler 分流（30010 / 30012 / 50000 各有专属文案）。

---

### P2 级（技术债 / 一致性）

---

**D-17｜P2｜`int(code)` 之外的第二套错误出口：`_raise_prepared_error` 猜错误码**
`backend/app/api/v1/couple/ai.py:704` — `int(code) if code.isdigit() else 50000`
触发场景：service 抛了未登记的业务码（如 `"90011"` 之外的码），静默变成 50000，用户看到「AI 服务异常」。`dual_summary_stream`（`ai.py:918-925`）登记了 3 个码，`viewpoint_analysis_stream`（`ai.py:952`）只登记 1 个——新增错误码时必然漏登记。
建议：未命中白名单时记 `logger.warning` 便于补登记；或改成「透传 code + 通用 message」而不是压成 50000。

---

**D-18｜P2｜`ai_limit()` 装饰器顺序与 `require_feature` 混用，冻结端点仍会触发限流计数**
`backend/app/api/v1/couple/ai.py:962-963` — `@router.post(..., dependencies=[Depends(require_feature("memory_card"))])` 叠 `@ai_limit()`
触发场景：`/ai/memory-card/stream` 已冻结（`features.py:50`），但用户调它仍消耗 `ai_limit` 配额。限流是按 user 还是全局需看 `limiter.py`，若是全局则**冻结功能被恶意调用可把正常用户全部锁死**。
建议：把 `require_feature` 放在 `ai_limit` 之外层（`@router` 的 `dependencies` 已先于函数体执行，实际顺序正确）——需确认 `limiter.py` 的 key 策略。

---

**D-19｜P2｜`mediation.py` 旧状态机无放弃终态**
`backend/app/api/v1/couple/mediation.py` 9 个端点，`reject`（`:64`）后 session 状态如何流转未在路由层闭环
触发场景：用户 A 发起调解，B 点拒绝，A 的会话卡在中间态，A 既不能重试也不能取消，只能新开一轮（旧轮次永久占着 `uk_ai_session_mediation_active` 唯一约束外的历史数据）。
建议：补 `POST /mediation/{id}/cancel`。

---

**D-20｜P2｜`relationship_review` 缺删除，负面记录无法清除**
`backend/app/api/v1/couple/ai.py:858`（list）、`:875`（detail）、`:889`（outcome）——无 DELETE
触发场景：用户做完一次情绪很差的复盘，不想要这条记录，删不掉。
建议：加 `DELETE /ai/review/{id}`（软删 + 排除出 `list_history`）。

---

## 三、角色/视角专项结论（P0-3）

### 已正确处理（红线达标）

| 链路 | 判据 | 证据 |
|---|---|---|
| **信件解读 / 回信 / 改写** | `letter.sender_id == user_id` 单一判据，同步与流式**共用** `_is_sender()` | `letter_ai_service.py:159-166`；分叉注入 `:173`、`:187`、`:284`、`:482` |
| **信件字段 description** | 显式声明「口径见提示词『这封信的来向』」，刻意不写死「对方」 | `schemas/ai_output.py:273-276`（类 docstring 解释了为什么）、`:283`、`:285` |
| **调解室 A/B 方** | `role_of(relation, user_id)` 由 `user_id` 推导 | `mediation_room_service.py:44-45`；调用点 `:95`、`:124` |
| **调解室称呼** | `party_labels` 读 `user_profile.gender`，性别不明回退「当事人A/B」，不让 LLM 猜 | `party_labels.py:39-53`；调用 `room_advisor_service.py:102-115` |
| **旧调解 rewrite_a/b** | 按 `my_role == "inviter"` 映射 | `mediation_service.py:1052-1053`、`:1378-1379`、`:442` |
| **双视角对照** | `r.user_id == user_id` 分流到 `side_self` / `side_partner` | `ai_service.py:2031-2041` |
| **观点分析** | `diary_repo.get_by_id(db, viewpoint_id, user_id)` 强绑定 user_id，单人资产 | `ai_service.py:2339` |
| **军师引用信件来源** | 提示词按「TA写给你的信」/「你写给TA的信」分叉 | `prompt_builder.py:536-539`（`QUOTE_LETTER_INSTRUCTION`） |
| **调解军师消息来源** | `sender_type` 三值枚举（user_a/user_b/advisor），客户端不会把军师发言标成「TA 说过」 | `models/mediation_room.py:60-62`；`RoomMessage.sender_user_id` 对 advisor 强制 NULL（`:164`） |
| **记忆归属** | `OWNERSHIP_BY_SOURCE` + `can_revoke_share()` 纯函数权限矩阵 | `memory_ownership.py:15-24`、`:70-83` |
| **AI 未配置拦截** | `_uaicfg.resolve()` 前置到 `begin()` 之前，覆盖所有流式端点 | `ai_generation_service.py:141-148`（注释明确说这是 2026-10-01 补的坑） |

### 仍有问题

**V-01｜P0｜观点分析的 `content_instruction` 写死了「他对你的理解」，与「观点是个人资产」定位冲突**
`backend/app/services/ai_service.py:2377` — `"用自己的话说清这段观点说明了什么、为什么值得（或不值得）记进你对他的理解"`
触发场景：**单身用户**（无伴侣，`relation_id` 允许为 None，见 `:2325-2327`）做观点分析，正文指令却说「记进**他对你的**理解」。模型会顺着这个提示去揣测一个不存在的「他」，可能凭空编造伴侣形象——正是本项目红线里「无中生有」的那一类。
建议：按有无 relation 分叉文案——有伴侣用「他对你们的了解」，无伴侣用「你对自己的了解」。

**V-02｜P1｜`LetterAnalysisOutput.emotion` 有默认值 `""`，方向口径靠提示词单点承载**
`backend/app/schemas/ai_output.py:283` — `emotion: str = Field("", description="情绪描述，口径见提示词「这封信的来向」")`
触发场景：`_validate_structured`（`ai_generation_service.py:249`）校验失败时返回 `None`，`to_payload` 回落 `row.structured_output or {}` → 客户端拿到**没有 emotion 字段的对象**。写信人视角下若模型误按收信人口吻填了 emotion，服务端**没有任何校验能发现方向反了**——落库字段 `risk_level` 由 `_resolve_risk`（`:280-292`）取高，但方向没有对应的守卫。
建议：落库时把 `_is_sender(letter, user_id)` 的结果一起写进 `ai_generation`（如 `direction` 字段），客户端展示与回读都有据可查；这也让 `get_saved` 回读时能校验方向一致性。

**V-03｜P1｜`rewrite_letter` 强制 `sender_id == user_id`，但提示词里画像是「伴侣画像」，方向单一**
`backend/app/services/letter_ai_service.py:334-335` + `:345-350`（`partner_profile=...`）
触发场景：设计上是「只允许改自己写的信」，逻辑自洽 ✅。但 `LETTER_REWRITE_PROMPT`（`:97-122`）里 `## 写信人画像` 这一节被换成了 `## 伴侣画像`，而正文指令说「保留用户的核心诉求」——「用户」和「伴侣」在同一段 prompt 里混用指代，模型可能把「保留用户诉求」理解成「保留伴侣诉求」。**这是 2026-10-01 那次方向修复漏掉的一处**（`understand` 和 `reply` 都分叉了，`rewrite` 因为只有单向所以没改，但 prompt 本身的指代没理清）。
建议：统一措辞为「写信人（你）→ 读信人（TA）」，避免「用户」「伴侣」混用。

**V-04｜P2｜`astrology_service.py` 的文案全部写死「TA」，但已按 user_id 区分语境**
`backend/app/services/astrology_service.py:69`、`:74`、`:93`、`:99`、`:104`、`:109`、`:113`、`:129`
触发场景：星座建议文案是「针对 TA 的性格」视角，若用户是**收信方**（正在读对方星座）语义正确；若是**写信方**（想改自己的表达）就反了。需确认调用方 `personality_service.get_personality_info` 对 self/partner 分别下发哪一套——本次未追到调用点，**疑似，需二次确认**。

**V-05｜额度耗尽 / 超时的落库时机（专项确认）**

| 情况 | 拦截位置 | 是否在落库前 | 结论 |
|---|---|---|---|
| AI 未配置（`AiConfigMissingError`） | `ai_generation_service.py:148` `_uaicfg.resolve()` | ✅ **是**（`begin()` 内、`:150` upsert 之前） | 已修，2026-10-01 补的 |
| 额度耗尽（`LlmQuotaError`） | `llm_client` 抛出 → `ai_generation_service.py:437` `except LlmError` | ⚠️ **否**，但 `_save(status=STATUS_FAILED)`（`:440`）会正确标记 failed | 不产生「空的成功记录」，但会留一条 `status=failed` 行。可接受 |
| LLM 超时 | `_REQUEST_TIMEOUT = 120.0`（`llm_client.py:49`）→ `httpx` 抛 `TimeoutException` → 包装为 `LlmError` | 同上 | 同上 |
| **量表分析** | `questionnaires.py:168` 同步调用 | ⚠️ 无 `ai_generation` 落库，但 `questionnaires.py:187-192` 把结果写 `submission.analysis_text` | D-03 相关 |

**同步版端点无「落库前拦截」问题**（`understand_letter` / `generate_reply` / `rewrite_letter` 不落 `ai_generation`，直接返回 dict）。

---

## 四、接口健壮性缺陷分类统计

| 缺陷类型 | 数量 | 典型代表位置 | 说明 |
|---|---|---|---|
| **1. 错误码类型不安全（`int()` 崩溃）** | **8 处** | `avatars.py:36`、`home.py:26`、`letters.py:39` | D-02。非数字 ValueError → 10000 |
| **2. 列表无分页 / 无 limit** | **6 处** | `memory_service.py:238`、`ai_service.py:1189`、`questionnaires.py:267`、`mediation_room_service.py:71`、`room_settlement_service.py:232`、`home_service.py:233` | D-06/D-07，随时间劣化 |
| **3. `.first()` 返回 None 后解引用** | **3 处** | `profiles.py:60`（`created_at.isoformat()`）、`:139`、`:182` | D-09。`home_service.py:67` 是正确写法 |
| **4. `dict[key]` 缺 key / 无默认** | **2 处** | `memory.py:65`（`body.get` 有默认 ✅）、`questionnaires.py:56-62`（`analysis.get` 有默认 ✅） | 实际风险低，schema 侧已用 `.get(k, default)` |
| **5. 唯一约束冲突未捕获 IntegrityError** | **2 处** | `auth_service.py:34`（`user.email`）、`couple_service.py:78`（`create_relation`） | D-05。正确写法在 `ai_task_repo.py:156` / `mediation_room_repo.py:168` |
| **6. 读-改-写 race（无乐观锁）** | **2 处** | `couple_service.py:57→78`（查无关系→建关系）、`diary_repo` `toggle_favorite` | 无 version 字段；`toggle_favorite` 双击会翻转两次 |
| **7. 事务/回滚位置错误** | **1 处** | `single/diary.py:61`（裸 `except Exception` 无 `except HTTPException`） | D-11 |
| **8. N+1 查询** | **1 处** | `letter_service.py:278`（批量删除逐条查+逐条 flush） | D-10 |
| **9. 批量入参无上限** | **2 处** | `diary_schema.py:36`（`ids: List[int]`）、`letter_schema.py` `BatchDeleteRequest` | D-10 |
| **10. 文件上传无清理 / 无鉴权下载** | **2 处** | `museum.py:48`、`main.py:188` | D-12 |
| **11. 路径参数无枚举白名单** | **2 处** | `ai.py:503`（`kind`）、`ai.py:504`（`target_type`） | D-15 |
| **12. `order_by` 注入** | **0 处** | — | ✅ 全部静态字段，grep 确认无动态拼接 |
| **13. LLM 返回非法 JSON** | 已妥善处理 ✅ | `ai_generation_service.py:234-252`、`questionnaire_analysis_service.py:143-146` | 双重兜底：结构化失败不致命 + 量表分析降级基础解读 |
| **14. SSE 中途断连** | 已妥善处理 ✅ | `ai.py:168-198`（`_disconnect_watcher`）、`ai.py:201-213`（生成器 finally 兜底）、`ai_generation_service.py:463-487` | 双路径置位 + 半成品落库，设计充分 |
| **15. 向量库不可用** | 已妥善处理 ✅ | `memory_service.py:356`（注释说明避免 1451） | — |
| **16. 鉴权漏挂** | **0 处** | — | ✅ 逐文件核对：`mediation_room.py` 用 `require_couple_mode`（内含 `get_current_user`）；`auth.py` 5 端点本就该公开；`ws.py` 手工校验 token |
| **17. `user_id` 从 body/query 取（越权高危）** | **0 处** | — | ✅ 全部走 `current_user.id`。`diary_repo.get_by_id(db, id, user_id)` 等仓储层签名强制带 user_id，是好设计 |
| **18. 对象级权限每层都判** | ⚠️ **2 处弱** | `ai.py:284`（`get_messages_by_session` 未先校验 session 归属，靠下游 `ai_service.submit_feedback` 兜）、`memory.py:176-182`（`get_memories_by_source` 未校验 diary 归属，靠 `diary_repo.get_by_id` 兜） | 目前下游有兜底，不构成漏洞，但**防御深度不足**：service 被新调用方复用时会失守 |
| **19. 列表泄露对方不该看到的字段** | **1 处** | `memory.py:32-35` → `memory_service.py:223` `filter(AiMemory.user_id == user_id)` | ✅ 按 user_id 严格隔离，private 记忆不会泄露。`get_couple_memories`（`:259`）另有 IDOR 守卫 |
| **20. 分页 count 正确性 / join 重复行** | **0 处问题** | `single/diary_repo.py:64` `total = query.count()` 在 `.offset()/.limit()` **之前** ✅ | — |

---

## 五、风险 Top 10 排序

按「用户撞上概率 × 后果严重度」：

| # | 缺陷 | 概率 | 后果 | 理由 |
|---|---|---|---|---|
| **1** | **D-03 LLM 在 API 进程同步执行** | 高（每次画像报告/问卷分析） | 极高（服务不可用） | 唯一会**整体拖垮服务**的问题。docker-compose 三服务分工是明确的架构约束，当前被两个端点破坏 |
| **2** | **D-04 `questionnaire_id` 被忽略** | 高（多份问卷的用户必中） | 高（看到错的数据） | 静默给错结果，用户无从察觉，且会据此做「写入画像」决策 → 错误写入画像，污染下游 |
| **3** | **D-02 `int(code)` 崩溃（8+ 处）** | 中 | 高（10000 不可诊断） | 一旦某个 service 新增中文错误码就立刻炸，且**炸的是用户主流程**。已确认 `memory_service.py:246,253` 就在抛中文码 |
| **4** | **D-06/D-07 六处列表无分页** | 高（随时间必然发生） | 中高（接口超时→10000） | 唯一「现在正常、三个月后必炸」的一类。用户感知是「App 越来越卡」 |
| **5** | **V-01 观点分析写死「他对你的理解」** | 中（单身用户 100%） | 高（AI 编造不存在的伴侣） | 直接违反红线，且是**内容正确性**问题——比崩溃更危险，因为用户会相信它 |
| **6** | **D-05 注册竞态** | 中（双击/多设备） | 中（10000 + 无法重试） | 有 3/min 限流，用户会卡死在注册失败 |
| **7** | **D-10 批量删除 N+1 + 无上限** | 中（用户整理旧信） | 中（长事务锁表） | 会拖慢同期其他写入 |
| **8** | **D-01 双 diary_repo 返回类型不一致** | 低（需有人改代码） | 高（静默 TypeError） | 是**给未来埋的雷**，不是当前 bug。但一旦踩中，症状与「AI 解析失败」难以区分 |
| **9** | **D-12 孤儿文件 + `/uploads` 无鉴权** | 高（持续累积） | 中（磁盘耗尽 / 隐私） | UUID 难猜使即时风险低，但长期必然爆磁盘 |
| **10** | **D-15 `kind`/`target_type` 无白名单 + 裸 raise** | 中 | 中低 | 目前只返回 `data: null`，但为后续加功能埋了坑 |

**关于「用户绝不能报错」的达标评估**：项目在**统一错误出口**上做得相当扎实——`main.py:245-387` 的全局处理器把 HTTPException / RequestValidationError / RateLimit / AiConfig / LlmQuota 全部收口到 `{code, message, data}` + HTTP 200，41 处裸 `HTTP_500` 里有 39 处带完整 detail 能被正常归一化。**真正的 500 风险不在错误处理框架，而在 D-02（`int()` 自身抛错绕过框架）和 D-05（IntegrityError 无捕获）**。

---

## 六、本次未能覆盖的范围

以下项目**未审计**，需额外手段才能确认：

### 6.1 需要真机 / 联调才能确认

| 未覆盖项 | 需要什么 |
|---|---|
| **客户端契约一致性**（168 接口 vs Android DTO） | `android/` 目录 Retrofit/Moshi 定义。本次**完全未读客户端代码**。响应体字段缺失/类型不符只能联调暴露 |
| **SSE 端到端时序**（12 个流式端点） | 需真机弱网（丢包/切后台/来电）实测。代码层面已确认断连处理设计完整，但「starlette 1.6 下 BackgroundTask 仍在同一 ASGI 调用内 await」这一前提（`ai.py:177-180` 的注释假设）**依赖具体 starlette 版本行为，必须实测** |
| **LLM 输出质量**（视角是否真的分对） | V-01/V-02/V-03 都是**提示词层**缺陷，代码无法证明模型一定理解。需构造「自己写的信 + 单身用户观点」两组用例实际调用模型验证 |
| **并发结算**（`claim_settlement` 的 `rowcount == 1`） | `mediation_room_repo.py:394` 的乐观锁写法正确，但需多 worker 压测确认 |
| **Chroma 不可用时的降级** | `rag_service.py` / `memory_retrieval.py` 的向量库异常路径未逐条追 |
| **邮件发送失败**（`email_service.py`） | SMTP 超时/退信的用户可见行为未验证 |

### 6.2 需要专项手段（未在本轮做）

| 未覆盖项 | 原因 / 需要什么 |
|---|---|
| **数据库 schema 与 ORM 的一致性** | 41 个 alembic 版本 vs 20 个 model 文件的全量 diff。本次只看 model，未跑 `alembic check` |
| **测试覆盖度评估** | `backend/tests/` 存在但未读。无法判断哪些缺陷有测试保护、哪些是测试盲区 |
| **N+1 的完整清单** | 只扫了 `.all()` 与显式循环。`memory_retrieval.py`、`rag_service.py` 的查询链未逐条展开 |
| **11 个 services 文件的业务正确性** | `astrology_service` / `birthplace_service` / `city_coords` / `conflict_detector` / `notification_service` / `safety_*` / `memory_*`（12 个文件）只做了 grep 级扫描，未通读 |
| **V-04 星座文案方向** | 需要追 `personality_service.get_personality_info` 的 self/partner 分发逻辑，本次未追到调用点 |
| **`agent/tools.py` 的工具权限** | Agent 路径（`ai.py:607-677`）会调 LangChain + 工具，工具是否有越权取数未审 |
| **docker-compose 实际配置** | 只读了三服务声明，未核 `.env` 变量是否真的按 `environment` 白名单下发到容器（`backend_deploy.tar.gz` 未解包） |
| **性能** | 无慢查询日志、无 EXPLAIN。`memory_retrieval` 的向量检索复杂度未评估 |

### 6.3 方法论边界

- 本轮**未执行任何代码**（只读审计，未跑测试、未启服务、未连 DB）
- 所有 `❌ 缺失` 判定基于**接口签名 grep**，若某功能由客户端本地缓存或其他端点组合实现，可能误判为缺失
- 「疑似，需二次确认」仅 V-04 一处；其余结论均已定位到具体行号并验证过上下文

---

## 七、给工程师的修复建议（优先级排序，不含代码）

**第一批（架构红线 + 数据正确性，建议立刻修）**
1. D-03：把 `questionnaires.py:168` 与 `profiles.py:194` 的 LLM 调用迁走（改投递 ai_task）
2. D-04：`analyze_for_user` 接收并使用 `questionnaire_id`
3. D-02 + D-08：抽 `to_business_code()` 统一工具，替换 8+ 处裸 `int(code)`
4. V-01：`ai_service.py:2377` 按有无伴侣分叉文案

**第二批（用户可撞上的功能缺陷）**
5. D-01：删除 `repositories/diary_repo.py`，统一到 `repositories/single/diary_repo.py`
6. D-05：`auth_service.register` 加 SAVEPOINT + `except IntegrityError`
7. D-06/D-07：六处列表加 `page/page_size` + 独立 `count()`
8. D-16：删掉 `questionnaires.py:176-184`、`:226-229` 的 `except Exception`，交给全局 handler

**第三批（技术债，可排期）**
9. D-10：批量删除改单条 IN + 加 `max_length`
10. D-09：统一 `created_at.isoformat()` 的 None 保护
11. D-12：加孤儿文件清理任务
12. D-15：`kind`/`target_type` 加白名单
13. D-11/D-13/D-14/D-20：错误码分段、异常原文回传、ai_generation 版本化、复盘删除

**架构层面的一句话结论**：分层是清晰的（router 只做协议转换、service 承载业务、repo 收口查询），没有发现 service 直接 import httpx 调模型的越界（`llm_client.py` 是唯一出口，已全量确认）；死代码**不是** v2.0 子目录副本（`api/v1/couple/` 下无子目录），而是 D-01 的同名 repo 分叉；统一错误出口框架设计质量高于一般项目水平，真正的漏洞在框架的**盲区**（`int()` 崩溃、IntegrityError）而非框架本身。
