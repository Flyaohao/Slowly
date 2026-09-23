cd /usr/src/couple-deploy

echo "############ 基础镜像 ############"
docker images --format '{{.Repository}}:{{.Tag}}  {{.Size}}' | head -5
echo
echo "############ 开始构建 ############"
echo "开始时间 $(date '+%H:%M:%S')"
free -m | head -2

# 用 tee 落盘，便于失败后回看；PIPESTATUS 保证构建失败能被发现
set -o pipefail
docker compose build 2>&1 | tee /tmp/compose_build.log | tail -70
BUILD_RC=${PIPESTATUS[0]}
echo "compose build 退出码 = $BUILD_RC"
echo "结束时间 $(date '+%H:%M:%S')"
echo

echo "############ 镜像产物 ############"
docker images --format '{{.Repository}}:{{.Tag}}  {{.Size}}  {{.CreatedSince}}'
echo
echo "############ 资源占用 ############"
free -m | head -2
df -h / | tail -1
echo
if [ "$BUILD_RC" != "0" ]; then
    echo "############ 构建失败，日志末尾 ############"
    tail -40 /tmp/compose_build.log
fi
exit $BUILD_RC
