#!/usr/bin/env bash
#
# 服务器端一键部署：拉代码 → 构建镜像 → 备份卷 → 启动 → 探活 → 失败回滚
#
#   bash scripts/deploy.sh                # 部署 master
#   bash scripts/deploy.sh v1.2.0         # 部署某个分支/tag
#   FORCE=1 bash scripts/deploy.sh        # 代码没变也强制重建
#
set -Eeuo pipefail

# 脚本自己也会被 git 更新：先拷一份到 /tmp 再执行，避免 bash 边读边改
if [ "${DEPLOY_REEXEC:-0}" != "1" ]; then
  cp "$0" /tmp/hoyo-deploy.sh
  DEPLOY_REEXEC=1 exec bash /tmp/hoyo-deploy.sh "$@"
fi

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$APP_DIR"

REF="${1:-master}"
FORCE="${FORCE:-0}"
COMPOSE="docker compose -f compose.yaml"
HEALTH_URL="http://127.0.0.1:8080/api/checkin/status"

log() { printf '[deploy] %s\n' "$*"; }
die() { log "ERROR: $*"; exit 1; }

# ── 1. 前置检查：缺什么立刻报，别跑到一半才发现 ──────────────
for bin in docker git curl flock; do
  command -v "$bin" >/dev/null 2>&1 || die "缺 $bin"
done
docker info >/dev/null 2>&1 || die "docker 引擎没起：systemctl start docker"
docker compose version >/dev/null 2>&1 || die "缺 docker compose 插件"
[ -f .env ]        || die "缺 .env（凭据不进 git，手工放一份并 chmod 600）"
[ -f compose.yaml ] || die "不在项目根目录"

# ── 2. 串行化：两个人同时部署会把容器搞乱 ────────────────────
exec 9>/tmp/hoyoagent-deploy.lock
flock -n 9 || die "另一个部署正在跑"

# ── 3. 拉代码 ───────────────────────────────────────────────
PREV="$(git rev-parse HEAD)"
git fetch --prune --tags origin

if git rev-parse --verify "refs/remotes/origin/$REF" >/dev/null 2>&1; then
  TARGET="origin/$REF"
elif git rev-parse --verify "refs/tags/$REF" >/dev/null 2>&1; then
  TARGET="refs/tags/$REF"
else
  die "找不到分支或 tag: $REF"
fi

git checkout --force --detach "$TARGET" >/dev/null
git reset --hard "$TARGET"            >/dev/null
NEW="$(git rev-parse HEAD)"
log "版本 $PREV → $NEW"

if [ "$PREV" = "$NEW" ] && [ "$FORCE" != "1" ] \
   && [ -n "$(docker compose ps --status running --quiet 2>/dev/null)" ]; then
  log "代码未变化，跳过（强制重建：FORCE=1 bash scripts/deploy.sh）"
  exit 0
fi

# ── 4. 构建：失败时旧容器还在跑，这是天然的保护 ──────────────
if ! $COMPOSE build --pull; then
  log "构建失败，旧版本继续提供服务"
  git reset --hard "$PREV" >/dev/null
  exit 1
fi

# ── 5. 备份数据卷：一天一份，别每天追加 ─────────────────────
mkdir -p backups
BACKUP="backups/$(date +%F).tgz"
if [ ! -f "$BACKUP" ]; then
  if docker run --rm -v hoyoagent_hoyo-data:/data -v "$APP_DIR/backups":/backup \
       alpine tar czf "/backup/$(basename "$BACKUP")" -C /data . 2>/dev/null; then
    log "已备份数据卷 → $BACKUP"
  else
    log "备份失败（不影响本次部署，但请检查）"
  fi
fi

# ── 6. 启动 ─────────────────────────────────────────────────
$COMPOSE up -d

# ── 7. 探活 90 秒，失败回滚 ─────────────────────────────────
ok=0
for _ in $(seq 1 45); do
  if curl -fsS --max-time 3 "$HEALTH_URL" >/dev/null 2>&1; then ok=1; break; fi
  sleep 2
done

if [ "$ok" != "1" ]; then
  log "探活失败，回滚到 $PREV"
  git reset --hard "$PREV" >/dev/null
  $COMPOSE build || die "回滚构建也失败，需要人工介入"
  $COMPOSE up -d
  die "已回滚。排查：docker compose logs --tail=100"
fi

# ── 8. 收尾 ─────────────────────────────────────────────────
docker image prune -f --filter "until=168h" >/dev/null 2>&1 || true
log "部署完成：$NEW"
$COMPOSE ps