"""由 landing.template.html 生成 index.html 与 manifest.json。

── 为什么有这一步 ────────────────────────────────────────────────────
页面里有两处必须写死的地址：下载按钮指向的 APK 直链、二维码里编码的落地页地址。
手改容易漏改，所以源稿写成带占位符的模板，由本脚本统一注入，可反复重跑。

── 二维码为什么不用跟着安装包变 ──────────────────────────────────────
二维码编码的是**落地页地址**（/app/），不是 APK 地址。只要落地页路径不变，
换多少次安装包、升多少个版本，二维码都不需要重新生成。
真正会随版本变化的是「落地页 → 安装包」这一跳，它由 manifest.json 一处驱动。

── 重新部署安装包时的正确流程 ────────────────────────────────────────
    0. 出包。**优先用 release 产物；没有则自动回落 debug**
       （android/app/build/outputs/apk/{release,debug}/）
       当前阶段对外分发的是 debug 包，故这条回落是必需的；release 一可用会自动优先。
    1. 上传到服务器 apk/ 目录，文件名 = couple-<版本>[-debug].apk
    2. 重跑本脚本 → index.html 与 manifest.json 一起更新
    3. 传上去（只换安装包时其实只需 manifest.json，index.html 只在文案变动时才要传）
二维码与页面结构全程不动。

用法（依赖 qrcode + pillow，装在 default venv 里，不在项目 venv 中）：
    <default venv>/python deploy/build_landing_page.py
    LANDING_BASE_URL=http://192.168.1.10:8000 <default venv>/python deploy/build_landing_page.py
    APK_NAME=couple-1.0.1.apk <default venv>/python deploy/build_landing_page.py

⚠️ 不要手改 index.html 与 manifest.json（它们是产物），改内容请改 landing.template.html。
"""

import base64
import datetime
import hashlib
import io
import json
import os
import re

import qrcode

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_DIR = os.path.join(ROOT, "backend", "static", "app")
TPL = os.path.join(APP_DIR, "landing.template.html")
OUT = os.path.join(APP_DIR, "index.html")
MANIFEST = os.path.join(APP_DIR, "manifest.json")

BASE = os.getenv("LANDING_BASE_URL", "http://182.92.194.78:8000").rstrip("/")
PAGE_URL = BASE + "/app/"
# 文件名默认按「版本号 + 构建类型」推导（见 main）。只有要强制指定时才设 APK_NAME。
APK_NAME_OVERRIDE = os.getenv("APK_NAME", "").strip()

QR_PLACEHOLDER = "__QR_DATA_URI__"
APK_PLACEHOLDER = "__APK_URL__"
# 安装包体积的**静态回退文案**。页面加载后会读同源 manifest.json 覆盖它；
# 但 manifest 读不到时（离线、路径被改）就会显示这行，所以它必须也是真值 ——
# 写死一个旧体积等于在兜底路径上骗用户。由本脚本注入。
APK_SIZE_PLACEHOLDER = "__APK_SIZE_TEXT__"


def qr_data_uri(text: str) -> str:
    """把文本编成二维码 PNG，再转成 data URI 内联。

    内联而非放独立图片文件：整个页面保持单文件、零外部请求，
    在断网/内网环境下也不会出现图片挂掉的白块。
    """
    qr = qrcode.QRCode(
        box_size=10,
        # 安静区必须 ≥4 个模块（QR 标准），否则扫码器在复杂背景下容易判不出边界。
        # 实测：border=1 且元素只有 78px 时，每模块不足 2 个物理像素，轻微模糊即扫不出；
        # 改 border=4 + 显示 112px（每模块约 3 像素）后模糊条件下仍可解。
        border=4,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
    )
    qr.add_data(text)
    qr.make(fit=True)
    # 深色用页面的 --ink，与整体配色一致；白底保证任何扫码器都能识别
    img = qr.make_image(fill_color="#241F21", back_color="#FFFFFF")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def read_app_version():
    """从 Gradle 配置读版本号，保证页面显示的版本与 App 自身一致（单一数据源）。"""
    gradle = os.path.join(ROOT, "android", "app", "build.gradle.kts")
    version, code = "1.0.0", 1
    if os.path.isfile(gradle):
        with open(gradle, "r", encoding="utf-8") as f:
            txt = f.read()
        m = re.search(r'versionName\s*=\s*"([^"]+)"', txt)
        if m:
            version = m.group(1)
        m = re.search(r"versionCode\s*=\s*(\d+)", txt)
        if m:
            code = int(m.group(1))
    return version, code


def find_apk():
    """找安装包：**优先 release，没有则回落 debug**。

    返回 (路径, 构建类型)；两者都找不到时返回 (None, None)，此时 manifest 的
    大小与校验值为空，页面会回落到 HTML 里写死的地址。

    —— 为什么要有 debug 回落：当前 App 尚未定稿，对外分发的是 debug 包；
       release 一可用会自动优先，不必回来改脚本。

    —— 为什么还要有 APK_PATH：manifest 里的大小与 SHA-256 必须描述
       **服务器上实际在服务的那一份**。本地构建产物随时会被重新编译，
       直接拿本地文件生成清单，就可能对外公布一个与下载到的文件对不上的校验值
       （而校验值的全部意义就是"对的"）。需要指向某个具体文件时用：
           APK_PATH=/path/to/couple-1.0.0-debug.apk python deploy/build_landing_page.py
    """
    override = os.getenv("APK_PATH", "").strip()
    if override:
        if not os.path.isfile(override):
            raise SystemExit("APK_PATH 指向的文件不存在：%s" % override)
        return override, ("debug" if "debug" in override.lower() else "release")

    base = os.path.join(ROOT, "android", "app", "build", "outputs", "apk")
    for build_type in ("release", "debug"):
        d = os.path.join(base, build_type)
        if not os.path.isdir(d):
            continue
        apks = [os.path.join(d, f) for f in os.listdir(d) if f.endswith(".apk")]
        if apks:
            return max(apks, key=os.path.getmtime), build_type
    return None, None


def build_manifest(version, code, apk_path, apk_name, build_type):
    """产出 manifest.json —— 重新部署安装包时唯一需要更新的文件。"""
    size = sha = None
    released = datetime.date.today().isoformat()
    if apk_path:
        size = os.path.getsize(apk_path)
        h = hashlib.sha256()
        with open(apk_path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        sha = h.hexdigest()
        released = datetime.date.fromtimestamp(os.path.getmtime(apk_path)).isoformat()
    return {
        "name": "慢慢说",
        "version": version,
        "versionCode": code,
        # 对外明示包类型：debug 包体积更大且带调试开关，别让人误以为是正式包
        "buildType": build_type or "unknown",
        "fileName": apk_name,
        "url": "/download/" + apk_name,
        "sizeBytes": size,
        "sha256": sha,
        "minAndroid": "8.0",
        "releasedAt": released,
        "pageUrl": PAGE_URL,
    }


def main():
    if not os.path.isfile(TPL):
        raise SystemExit("找不到模板：%s" % TPL)

    with open(TPL, "r", encoding="utf-8") as f:
        html = f.read()

    missing = [p for p in (QR_PLACEHOLDER, APK_PLACEHOLDER, APK_SIZE_PLACEHOLDER) if p not in html]
    if missing:
        raise SystemExit(
            "模板里缺少占位符 %s —— 模板可能被误改过，不要继续生成"
            % "、".join(missing)
        )

    version, code = read_app_version()
    apk, build_type = find_apk()

    if APK_NAME_OVERRIDE:
        apk_name = APK_NAME_OVERRIDE
    elif build_type == "debug":
        apk_name = "couple-%s-debug.apk" % version
    else:
        apk_name = "couple-%s.apk" % version

    # 包类型以**最终文件名**为准：APK_PATH 指向临时副本时，路径里未必带 "debug"，
    # 只看路径会把 debug 包标成 release（manifest 里那个字段是给人看的，
    # 标错就等于骗人）。
    if "debug" in apk_name.lower():
        build_type = "debug"

    apk_url = BASE + "/download/" + apk_name

    manifest = build_manifest(version, code, apk, apk_name, build_type)

    uri = qr_data_uri(PAGE_URL)
    size_text = (
        "约 %.1f MB" % (manifest["sizeBytes"] / 1024 / 1024)
        if manifest["sizeBytes"]
        else "体积以实际下载为准"
    )
    html = (
        html.replace(QR_PLACEHOLDER, uri)
        .replace(APK_PLACEHOLDER, apk_url)
        .replace(APK_SIZE_PLACEHOLDER, size_text)
    )

    with open(OUT, "w", encoding="utf-8") as f:
        f.write(html)
    with open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print("已生成 %s（%.1f KB）" % (OUT, os.path.getsize(OUT) / 1024))
    print("已生成 %s" % MANIFEST)
    print("  落地页地址：%s" % PAGE_URL)
    print("  安装包直链：%s" % apk_url)
    print("  App 版本  ：v%s (versionCode %s)" % (version, code))
    print("  包类型    ：%s" % (build_type or "未找到产物"))
    if manifest["sizeBytes"]:
        print("  安装包大小：%.1f MB" % (manifest["sizeBytes"] / 1024 / 1024))
        print("  SHA-256   ：%s…" % manifest["sha256"][:16])
    else:
        print(
            "  [warn] release/debug 目录下都没有 APK，manifest 里大小与校验值为空"
            "（打好包后再跑一次即可）"
        )
    print("  二维码    ：%d 字符 base64" % len(uri))

    # 产物里不该再残留占位符
    if any(p in html for p in (QR_PLACEHOLDER, APK_PLACEHOLDER, APK_SIZE_PLACEHOLDER)):
        raise SystemExit("[FAIL] 产物中仍存在占位符")


if __name__ == "__main__":
    main()
