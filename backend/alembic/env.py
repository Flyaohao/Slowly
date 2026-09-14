import sys
from logging.config import fileConfig
from pathlib import Path

from sqlalchemy import engine_from_config, pool
from alembic import context

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.config import DB_URL
from app.core.database import Base

# 整体导入 app.models，确保其 __init__ 中登记的所有模型都注册进 Base.metadata。
# 这里原本是一份手工维护的模型清单，漏掉了 v1.6.5 之后的模型（如 dual_perspective /
# museum / practice / anniversary / avatar 以及 v2.0 的 diary_entry 等），
# 导致 autogenerate 看不见这些表，会反过来提议把它们 DROP 掉。
import app.models  # noqa: F401,E402

config = context.config
config.set_main_option("sqlalchemy.url", DB_URL)

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def run_migrations_offline() -> None:
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        render_as_batch=True,
    )

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            render_as_batch=True,
        )

        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
