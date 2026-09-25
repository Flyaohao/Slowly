cd /usr/src/couple-deploy

echo "############ 1. down（停掉 restart 循环）############"
docker compose down 2>&1 | tail -10
echo

echo "############ 2. up -d ############"
docker compose up -d 2>&1 | tail -20
echo
docker compose ps 2>&1
echo

echo "############ 3. 等待就绪（迁移/种子/向量库）############"
READY=0
for i in $(seq 1 36); do
    # /docs 与 /openapi.json 在安全加固后需 Basic 认证，匿名访问返回 401 ——
    # 那同样说明进程已起来。所以不能只认 200：加固一上线，只认 200 的判据会误判超时。
    CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 5 http://127.0.0.1:8000/docs 2>/dev/null)
    if [ "$CODE" = "401" ] || [ "$CODE" = "200" ]; then
        echo "第 ${i} 次探测：HTTP ${CODE} —— 应用已就绪"
        READY=1
        break
    fi
    echo "第 ${i} 次探测：HTTP ${CODE:-无响应}，等 10s"
    sleep 10
done
echo

echo "############ 4. 容器日志 ############"
docker compose logs --tail=100 --no-log-prefix 2>&1
echo

echo "############ 5. 资源 ############"
docker stats --no-stream --format '{{.Name}}  CPU {{.CPUPerc}}  内存 {{.MemUsage}}' 2>&1
free -m | head -2

if [ "$READY" = "1" ]; then echo "RESULT=READY"; else echo "RESULT=TIMEOUT"; fi
