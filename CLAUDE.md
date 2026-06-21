# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Couple AI Translator** (情侣 AI 翻译器) — a relationship companion app with an Android client and Python/FastAPI backend. The app provides AI-powered relationship features: message translation, conflict mediation, love letters, dual-perspective exercises, a relationship museum, questionnaires, and an avatar system.

## Repository Structure

```
couple/
├── android/          # Android client (Kotlin + Jetpack Compose)
├── backend/          # FastAPI backend (Python)
├── spec/             # Design documents (Chinese)
│   ├── 分析/         # Requirements & functional analysis
│   ├── 设计/         # Architecture, DB, UI, code style specs
│   ├── 实现/         # Implementation notes
│   └── index.html    # HTML prototype
├── deploy_update.py  # SSH deployment script
└── WORKFLOW_CONFIG.md # Role-based workflow (PM→ARCH→DEV→FE→QA→SHIP)
```

## Backend (FastAPI + SQLAlchemy + MySQL)

### Commands

```bash
# Run backend locally
cd backend && python main.py
# Starts uvicorn on 0.0.0.0:8000 with hot-reload

# Install dependencies
pip install -r requirements.txt

# Database migrations (Alembic)
cd backend && alembic upgrade head
cd backend && alembic revision --autogenerate -m "description"

# Seed data
cd backend && python scripts/seed_questionnaire.py
cd backend && python scripts/seed_knowledge.py
cd backend && python scripts/seed_ai_scenes.py
cd backend && python scripts/seed_practices.py
cd backend && python scripts/seed_avatar_assets.py
```

### Environment

Backend requires `.env` file (see `backend/.env.example`):
- `DB_URL` — MySQL connection string (pymysql driver)
- `JWT_SECRET` — Token signing key
- `DB_CHARSET` — defaults to `utf8mb4`

### Architecture Layers

All API routes are prefixed `/api/v1/`. The code follows strict layering:

| Layer | Directory | Responsibility |
|-------|-----------|---------------|
| API | `app/api/v1/` | Request handling, param validation, HTTP errors. Uses `APIRouter` per resource. |
| Schemas | `app/schemas/` | Pydantic request/response models. Shared `ApiResponse` envelope (`code`, `message`, `data`). |
| Services | `app/services/` | Business logic. Stateless functions that take `Session` + args. |
| Repositories | `app/repositories/` | Database access via SQLAlchemy. Functions take `Session` and return model instances. |
| Models | `app/models/` | SQLAlchemy ORM models inheriting `app.core.database.Base`. |
| Security | `app/security/` | JWT token creation/decoding (`HS256`), password hashing (`bcrypt`). |
| Core | `app/core/` | Config, DB engine/session, FastAPI dependencies (`get_current_user`, `get_current_relation`), rate limiter. |

### API Response Format

All endpoints return the unified envelope defined in `app/schemas/common.py`:
```json
{"code": 0, "message": "success", "data": {...}}
```
Errors use module-prefixed codes: `1xxxx` (general), `2xxxx` (auth), `3xxxx` (couple), `4xxxx` (questionnaire), `5xxxx` (AI), `6xxxx` (letters).

### Auth Flow

JWT-based with access (2h) + refresh (7d) tokens. `get_current_user` dependency extracts user from `Authorization: Bearer <token>`. `get_current_relation` extends this with the active couple binding. A separate `private` token type (30min) gates access to sensitive personal data.

### AI Services

Key AI-related services in `app/services/`:
- `ai_service.py` — Main chat orchestration
- `prompt_builder.py` — Prompt template construction
- `scene_router.py` — Routes requests to appropriate AI scene handlers
- `rag_service.py` — Retrieval-augmented generation
- `safety_service.py` — Input/output safety filtering (manipulation, threats, self-harm, etc.)
- `output_validator.py` — Structured AI output validation
- `conflict_detector.py` — Conflict detection in messages
- `letter_ai_service.py` — AI-assisted letter writing (understand, rewrite, generate reply)
- `mediation_service.py` — Multi-step conflict mediation flow

### WebSocket

`app/api/v1/ws.py` handles real-time communication endpoints.

## Android Client (Kotlin + Jetpack Compose)

### Commands

```bash
# Build debug APK
cd android && ./gradlew assembleDebug

# Build release APK
cd android && ./gradlew assembleRelease

# Run tests
cd android && ./gradlew test

# Clean build
cd android && ./gradlew clean
```

### Tech Stack

- **Language**: Kotlin, JVM target 17
- **UI**: Jetpack Compose with Material 3
- **DI**: Dagger Hilt
- **Networking**: Retrofit 2 + OkHttp 4 + Moshi (JSON serialization)
- **Navigation**: Jetpack Navigation Compose
- **Image loading**: Coil
- **Async**: Kotlin Coroutines
- **Storage**: DataStore Preferences (for tokens)
- **Min SDK**: 26, **Target SDK**: 35

### Architecture

MVVM pattern with clean layering:

| Layer | Directory | Responsibility |
|-------|-----------|---------------|
| Network | `network/` | `ApiService` (Retrofit interface), `ApiClient` (Hilt DI module), token interceptors |
| Data Models | `data/model/` | DTOs matching backend schemas (`*Dto.kt` with nested request/response classes) |
| Repositories | `data/repository/` | One per feature domain, calls `ApiService`, returns `Resource<T>` |
| UI Screens | `ui/<feature>/` | Composable screens + ViewModels per feature |
| Navigation | `navigation/` | `Screen` enum (route strings) + `NavGraph` composable |
| Common | `common/` | `Resource<T>` sealed class (Loading/Success/Error), `Constants`, `Extensions` |

### Network Configuration

- Base URL is set in `app/build.gradle.kts` via `BuildConfig.BASE_URL`
- `TokenInterceptor` adds JWT to requests
- `TokenAuthenticator` handles automatic token refresh on 401
- `ApiResponse<T>` wrapper matches backend's `{code, message, data}` envelope

### Key Screens (from `Screen.kt`)

Auth: Login → Register → ForgotPassword
Main: Home → Mailbox → AiChat
Features: Questionnaire → Profile/CoupleProfile, Letters, Mediation flow, DualPerspective, Museum, Practices, Anniversaries, Wishlists, Avatar customization, ColdWar mode, Memory viewer

## Design Documents

Spec documents in `spec/` are in Chinese and define the full product:
- `spec/设计/总体架构设计.md` — System architecture (Mermaid diagrams)
- `spec/设计/数据库设计.md` — Database schema
- `spec/设计/功能设计.md` — Feature specifications
- `spec/设计/前端界面设计.md` — UI/UX design (61KB, comprehensive)
- `spec/设计/代码风格规则约束.md` — Code style rules for both backend and Android
- `spec/分析/需求分析文档.md` — Requirements analysis
- `spec/分析/功能分析文档.md` — Functional analysis

## Deployment

`deploy_update.py` zips the backend, uploads via SFTP to the production server, extracts, restarts uvicorn, and runs smoke tests. Runs on Python with `paramiko`.

## Coding Conventions

From `spec/设计/代码风格规则约束.md`:
- **Backend**: snake_case for files/functions/variables, PascalCase for classes, kebab-case for API routes
- **Android**: PascalCase for Screens/ViewModels/UiState, camelCase for everything else
- **DB tables**: lowercase snake_case, `id` primary key, `created_at`/`updated_at` timestamps
- **API routes**: RESTful `/api/v1/resource` pattern
- **AI prompts**: Must be in separate template files, not hardcoded in business logic
- **Privacy**: AI memory must be viewable/deletable; private "military advisor" mode hidden from partner by default
