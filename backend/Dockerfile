# 后端镜像
#
# 为什么用容器：线上服务器是 Debian + Python 3.8，
# 而本项目的 AI 链路依赖 langchain 1.x 与 chromadb，两者都要求 Python >= 3.9。
# 直接升级线上 Python 风险高（系统包管理器的依赖链），容器把这一层隔离掉，
# 同时顺带解决了「换机器重装环境」的问题。
#
# Python 版本选 3.13 是为了与本机验证环境一致 —— 依赖组合已在 3.13 上跑通全部验证脚本。

FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    TZ=Asia/Shanghai

WORKDIR /app

# build-essential：少数依赖没有 manylinux 轮子时需要现场编译
# default-mysql-client：运维时进容器排查数据库用
# curl：健康检查
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        build-essential curl default-mysql-client \
 && rm -rf /var/lib/apt/lists/*

# 依赖单独一层，代码改动不会导致重复装依赖
COPY requirements.txt ./
RUN pip install --upgrade pip && pip install -r requirements.txt

COPY . .
RUN chmod +x docker-entrypoint.sh

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8000/docs > /dev/null || exit 1

ENTRYPOINT ["./docker-entrypoint.sh"]
