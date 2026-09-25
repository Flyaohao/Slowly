"""由 landing.template.html 生成 index.html 与 manifest.json。

── 为什么有这一步 ────────────────────────────────────────────────────
页面里有两处必须写死的地址：下载按钮指向的 APK 直链、二维码里编码的落地页地址。
手改容易漏改，所以源稿写成带占位符的模板，由本脚本统一注入，可反复重跑。

── 二维码为什么不用跟着安装包变 ──────────────────────────────────────
二维码编码的是**落地页地址**（/app/），不是 APK 地址。只要落地页路径不变，
换多少次安装包、升多少个版本，二维码都不需要重新生成。
真正会随版本变化的是「落地页 → 安装包」这一跳，它由 manifest.json 一处驱动。

── 重新部署安装包时的正确流程 ────────────────────────────────────────
    1. 打好新的 release 包（android/app/build/outputs/apk/release/）
    2. 上传到服务器 app-download/ 目录，文件名带上版本号
    3. 重跑本脚本 → index.html 与 manifest.json 一起更新
    4. 把 manifest.json 传到服务器（index.html 只在文案变动时才需要传）
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
APK_NAME = os.getenv("APK_NAME", "couple-1.0.0.apk")
APK_URL = BASE + "/download/" + APK_NAME

QR_PLACEHOLDER = "__QR_DATA_URI__"
APK_PLACEHOLDER = "__APK_URL__"


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
    """找 release 产物；没构建过则返回 None（此时 manifest 的大小与校验值为空）。"""
    d = os.path.join(ROOT, "android", "app", "build", "outputs", "apk", "release")
    if not os.path.isdir(d):
        return None
    apks = [os.path.join(d, f) for f in os.listdir(d) if f.endswith(".apk")]
    return max(apks, key=os.path.getmtime) if apks else None


def build_manifest(version, code, apk_path):
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
        "fileName": APK_NAME,
        "url": "/download/" + APK_NAME,
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

    missing = [p for p in (QR_PLACEHOLDER, APK_PLACEHOLDER) if p not in html]
    if missing:
        raise SystemExit(
            "模板里缺少占位符 %s —— 模板可能被误改过，不要继续生成"
            % "、".join(missing)
        )

    version, code = read_app_version()
    apk = find_apk()
    manifest = build_manifest(version, code, apk)

    uri = qr_data_uri(PAGE_URL)
    html = html.replace(QR_PLACEHOLDER, uri).replace(APK_PLACEHOLDER, APK_URL)

    with open(OUT, "w", encoding="utf-8") as f:
        f.write(html)
    with open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print("已生成 %s（%.1f KB）" % (OUT, os.path.getsize(OUT) / 1024))
    print("已生成 %s" % MANIFEST)
    print("  落地页地址：%s" % PAGE_URL)
    print("  安装包直链：%s" % APK_URL)
    print("  App 版本  ：v%s (versionCode %s)" % (version, code))
    if manifest["sizeBytes"]:
        print("  安装包大小：%.1f MB" % (manifest["sizeBytes"] / 1024 / 1024))
        print("  SHA-256   ：%s…" % manifest["sha256"][:16])
    else:
        print(
            "  [warn] 未找到 release 产物，manifest 里大小与校验值为空"
            "（打好包后再跑一次即可）"
        )
    print("  二维码    ：%d 字符 base64" % len(uri))

    # 产物里不该再残留占位符
    if QR_PLACEHOLDER in html or APK_PLACEHOLDER in html:
        raise SystemExit("[FAIL] 产物中仍存在占位符")


if __name__ == "__main__":
    main()
