"""远程执行器：在线上服务器上跑一条命令，回显输出。

凭据全部从环境变量读取，不落盘、不进版本库：
    COUPLE_SSH_HOST      服务器地址
    COUPLE_SSH_USER      登录用户（默认 root）
    COUPLE_SSH_PASSWORD  登录密码
    COUPLE_DEPLOY_PATH   部署目录（默认 /usr/src/couple-backend）

用法（不要把密码写进命令行，避免留在 shell 历史里）：
    COUPLE_SSH_PASSWORD=xxx python deploy_ssh.py "docker --version"
    COUPLE_SSH_PASSWORD=xxx python deploy_ssh.py --script local.sh

与 deploy_update.py 的区别：这个只负责「执行」，不做打包/重启等具体动作，
方便部署时分步排查，出错时能看清是哪一步挂的。
"""

import os
import shlex
import sys

import paramiko

HOST = os.getenv("COUPLE_SSH_HOST", "")
USER = os.getenv("COUPLE_SSH_USER", "root")
PASSWORD = os.getenv("COUPLE_SSH_PASSWORD", "")
DEPLOY_PATH = os.getenv("COUPLE_DEPLOY_PATH", "/usr/src/couple-backend")
PORT = int(os.getenv("COUPLE_SSH_PORT", "22"))

if not HOST or not PASSWORD:
    raise SystemExit(
        "缺少部署配置：需要环境变量 COUPLE_SSH_HOST 与 COUPLE_SSH_PASSWORD。"
    )


def main():
    argv = sys.argv[1:]
    if argv and argv[0] == "--script":
        with open(argv[1], "r", encoding="utf-8") as f:
            command = f.read()
    elif argv and argv[0] == "--upload":
        local, remote = argv[1], argv[2]
        # MSYS/Git Bash 会把以 / 开头的 argv 自动转成 Windows 路径
        # （/usr/src/... → D:/java1/Git/usr/src/...），远端 cat/SFTP 必失败，
        # 且报错（FileNotFoundError / Socket closed）完全看不出是路径被转换了。
        # 这里显式拦截，给出可操作的提示；根治办法是带 MSYS_NO_PATHCONV=1 运行。
        if not remote.startswith("/"):
            raise SystemExit(
                f"远端路径必须以 / 开头，收到：{remote}\n"
                "若在 Git Bash 中运行，这是 MSYS 路径转换所致，"
                "请改用：MSYS_NO_PATHCONV=1 python deploy/deploy_ssh.py ..."
            )
        # paramiko 5.x 起 put() 不再把以 / 结尾的 remote 当目录拼文件名，
        # 会直接拿目录本身开写、必失败；先归一成完整文件路径，语义与旧版一致。
        if remote.endswith("/"):
            remote = remote + os.path.basename(local)
        try:
            client = _connect()
            sftp = client.open_sftp()
            sftp.put(local, remote)
            target = sftp.stat(remote).st_size
            sftp.close()
            client.close()
            print(f"[OK] 已上传 {local} -> {remote}（{target} 字节，SFTP）")
            return 0
        except Exception as exc:  # noqa: BLE001 —— 任何 SFTP 异常都退回 exec 管道
            # 2026-09-16 起线上 sftp-server 对**任何写操作**都回 SSH_FX_FAILURE：
            # 连 /tmp 下 7 字节的小文件都建不出来，读操作同样异常，但 exec 通道
            # 一切正常。不为此改部署流程，直接在这里降级成 `cat > 目标` + stdin。
            print(f"[warn] SFTP 不可用（{exc.__class__.__name__}: {exc}），改用 exec 管道上传")
        return _put_via_exec(local, remote)
    elif argv:
        command = " ".join(argv)
    else:
        command = sys.stdin.read()

    client = _connect()
    stdin, stdout, stderr = client.exec_command(command, timeout=1800)
    # stderr 合并进 stdout 一起读。
    # 分开读会死锁：先把 stdout 读到 EOF，而远端往 stderr 写满了 SSH 通道窗口后
    # 就会被阻塞、于是 stdout 永远等不到 EOF —— docker build 这类大量写 stderr
    # 的命令必定踩中，表现为「命令明明跑完了，本地却一直挂着没有输出」。
    channel = stdout.channel
    channel.set_combine_stderr(True)
    out = channel.makefile("rb").read().decode("utf-8", errors="replace")
    code = channel.recv_exit_status()
    client.close()

    if out:
        print(out.rstrip())
    return code


def _put_via_exec(local: str, remote: str) -> int:
    """用 exec 通道把本地文件灌进远端（`cat > 目标` + stdin 管道）。

    远端路径以 `/` 结尾时按目录处理，拼上本地文件名，语义与 SFTP 的 put 对齐
    —— 部署脚本里写的一直是目录形式（`/usr/src/couple-deploy/`）。
    """
    if remote.endswith("/"):
        remote = remote + os.path.basename(local)
    with open(local, "rb") as f:
        data = f.read()

    # 写到一半 SSH 连接断掉（Socket is closed）时整包重试一次，重试也失败
    # 才报错，绝不静默返回成功。注意：2026-09-26 排查的连续两次 Socket closed
    # 实为 Git Bash 把远端路径 MSYS 转换所致（见 --upload 分支的拦截），
    # 此重试是留给真实网络瞬断的兜底。
    last_error = None
    for attempt in (1, 2):
        client = _connect()
        try:
            stdin, stdout, stderr = client.exec_command(
                "cat > %s" % shlex.quote(remote), timeout=1800
            )
            stdin.write(data)
            stdin.flush()
            # 关掉写方向即向远端 cat 发 EOF，否则它会一直等更多输入
            stdin.channel.shutdown_write()
            out = stdout.read().decode("utf-8", errors="replace")
            err = stderr.read().decode("utf-8", errors="replace")
            code = stdout.channel.recv_exit_status()
            # 回读校验：字节数对不上就当作失败，别让半截包进到解包步骤
            size = client.exec_command(
                "stat -c %%s %s" % shlex.quote(remote), timeout=60
            )[1]
            remote_size = size.read().decode().strip()
        except OSError as exc:
            last_error = exc
            print(f"[warn] exec 上传中断（第 {attempt} 次：{exc}），重传")
            continue
        finally:
            client.close()

        if out or err:
            print((out + err).rstrip())
        if remote_size != str(len(data)):
            print(f"[FAIL] 本地 {len(data)} 字节，远端 {remote_size or '?'} 字节")
            return 1
        print(f"[OK] 已上传 {local} -> {remote}（{len(data)} 字节，exec 管道）")
        return code
    raise SystemExit(f"上传失败（两次均被中断）：{last_error}")


def _connect():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, port=PORT, username=USER, password=PASSWORD, timeout=20)
    return client


if __name__ == "__main__":
    sys.exit(main())
