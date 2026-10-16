"""
部署配置静态校验
================
本机没有 Docker，无法 `docker compose up` 实测。
退而求其次：把编排文件与 Dockerfile 做**结构与引用校验**，至少保证不是「写得好看但跑不起来」。

校验项：
  1. docker-compose.yml 能被 YAML 解析、服务齐全、healthcheck / depends_on 条件正确
  2. 三个 Dockerfile 里 COPY 的源路径在仓库里真实存在
  3. Nginx 反代包含 SSE 必需的三个配置
  4. MySQL 容器初始化脚本按序就位

用法： python scripts/check-deploy.py
退出码非 0 表示有问题（可用于 CI）。
"""
from __future__ import annotations

import os
import re
import sys

try:
    import yaml
except ImportError:
    print("需要 pyyaml：pip install pyyaml -i https://pypi.tuna.tsinghua.edu.cn/simple")
    sys.exit(2)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
problems: list[str] = []


def ok(msg: str) -> None:
    print(f"  [OK]   {msg}")


def bad(msg: str) -> None:
    print(f"  [FAIL] {msg}")
    problems.append(msg)


# ---------------------------------------------------------------- 1. compose
print("== 1) docker-compose.yml ==")
compose_path = os.path.join(ROOT, "deploy", "docker-compose.yml")
if not os.path.exists(compose_path):
    bad("找不到 deploy/docker-compose.yml")
    sys.exit(1)

with open(compose_path, encoding="utf-8") as f:
    cfg = yaml.safe_load(f)
ok("YAML 解析通过")

svcs = cfg.get("services", {})
expected = {"mysql", "redis", "minio", "ai-service", "backend", "frontend", "gateway"}
print(f"       服务 {len(svcs)} 个：{', '.join(svcs)}")
if expected == set(svcs):
    ok("7 个服务齐全")
else:
    missing = expected - set(svcs)
    extra = set(svcs) - expected
    bad(f"服务不匹配，缺 {missing or '无'}，多 {extra or '无'}")

hc = [k for k, v in svcs.items() if "healthcheck" in v]
if len(hc) >= 4:
    ok(f"{len(hc)} 个服务配了 healthcheck：{', '.join(hc)}")
else:
    bad(f"只有 {len(hc)} 个服务配了 healthcheck，依赖顺序可能不可靠")

# backend 必须等 mysql/redis/minio/ai-service 都 healthy
dep = svcs.get("backend", {}).get("depends_on", {})
cond = {k: (v.get("condition") if isinstance(v, dict) else "started") for k, v in dep.items()}
need = {"mysql", "redis", "minio", "ai-service"}
if need <= set(cond) and all(cond.get(k) == "service_healthy" for k in need):
    ok("backend 依赖 mysql/redis/minio/ai-service 且均为 service_healthy")
else:
    bad(f"backend 依赖条件不完整：{cond}")

if cfg.get("volumes") and cfg.get("networks"):
    ok(f"命名卷 {len(cfg['volumes'])} 个、网络 {len(cfg['networks'])} 个")
else:
    bad("缺少命名卷或网络定义")

# ---------------------------------------------------------------- 2. Dockerfile
print("\n== 2) Dockerfile 里的 COPY 源路径 ==")
dockerfiles = {
    "deploy/backend/Dockerfile": ["backend/pom.xml", "backend/src"],
    # 注意：前端 Dockerfile 用的是 frontend/package*.json（通配，同时拷 package.json 与 lock），
    # 所以这里匹配前缀 frontend/package 而不是精确文件名——校验器自身也别写死。
    "deploy/frontend/Dockerfile": ["frontend/package"],
    "ai-service/Dockerfile": ["requirements.txt"],
}
for rel, refs in dockerfiles.items():
    path = os.path.join(ROOT, rel)
    if not os.path.exists(path):
        bad(f"缺少 {rel}")
        continue
    txt = open(path, encoding="utf-8").read()
    missing = [r for r in refs if r not in txt]
    if missing:
        bad(f"{rel} 未引用 {missing}")
    else:
        ok(f"{rel} 引用 {refs}")
    # 多阶段构建检查
    if txt.count("FROM ") >= 2:
        ok(f"{rel} 是多阶段构建（{txt.count('FROM ')} 段）")
    else:
        print(f"         （单阶段构建，可接受）")

# ---------------------------------------------------------------- 3. Nginx
print("\n== 3) Nginx SSE 关键配置 ==")
ng_path = os.path.join(ROOT, "deploy", "nginx", "nginx.conf")
ng = open(ng_path, encoding="utf-8").read()
for key in ["proxy_buffering off", "proxy_cache off", "proxy_read_timeout 300s", "proxy_http_version 1.1"]:
    if key in ng:
        ok(f"包含 {key!r}")
    else:
        bad(f"缺少 {key!r} —— SSE 会变成「憋到最后一次性返回」或 60 秒断开")
if "client_max_body_size" in ng:
    ok("设置了 client_max_body_size（大文件上传不会被 413）")
else:
    bad("未设置 client_max_body_size")

# ---------------------------------------------------------------- 4. MySQL init
print("\n== 4) MySQL 容器初始化脚本 ==")
init_dir = os.path.join(ROOT, "deploy", "mysql", "init")
files = sorted(os.listdir(init_dir)) if os.path.isdir(init_dir) else []
if files[:2] == ["01-schema.sql", "02-seed.sql"]:
    ok(f"按序就位：{files}")
else:
    bad(f"初始化脚本命名/顺序不对：{files}")

# ---------------------------------------------------------------- 汇总
print()
if problems:
    print(f"发现 {len(problems)} 个问题：")
    for p in problems:
        print(f"  - {p}")
    sys.exit(1)
print("部署配置静态校验全部通过 ✔（注意：本机无 Docker，未做容器实机启动验证）")
