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
#
# 源换成阿里云：Debian 官方源在国内实测会卡在 apt-get update（9.6MB 索引拉十几分钟
# 都不动），这一步比镜像本身更容易让构建失败。sed 同时覆盖 deb822（trixie 用的
# sources.list.d/debian.sources）与旧式 sources.list，两种格式都不会漏。
RUN set -eux; \
    for f in /etc/apt/sources.list /etc/apt/sources.list.d/debian.sources; do \
        [ -f "$f" ] && sed -i 's|deb.debian.org|mirrors.aliyun.com|g' "$f" || true; \
    done; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        build-essential curl default-mysql-client; \
    rm -rf /var/lib/apt/lists/*

# 依赖单独一层，代码改动不会导致重复装依赖
# pip 走清华源：langchain + chromadb 这一组在国内直连 PyPI 很慢。
# 注意别用阿里云 pypi 源——它对刚发布的版本同步滞后（实测 uvicorn 0.53.0 晚了一天才到，
# 且 PyMySQL/PyJWT 偶发 404），清华 TUNA 同步及时且 18 个依赖全部命中。
# 换回官方源只需删掉 -i 参数（版本是 pin 死的，两边的包内容一致）。
COPY requirements.txt ./
RUN pip install --upgrade pip -i https://pypi.tuna.tsinghua.edu.cn/simple/ \
 && pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple/

COPY . .
RUN chmod +x docker-entrypoint.sh

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8000/docs > /dev/null || exit 1

ENTRYPOINT ["./docker-entrypoint.sh"]
