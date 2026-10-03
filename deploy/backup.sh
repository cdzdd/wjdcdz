#!/bin/sh
set -eu
repo_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
backup_dir="$repo_dir/.runtime/backups"
mkdir -p "$backup_dir"
output="$backup_dir/fund_sim-$(date +%Y%m%d-%H%M%S).sql"
docker compose --env-file "$repo_dir/.env" -f "$repo_dir/deploy/compose.yaml" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --user="$MYSQL_USER" --single-transaction --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "$MYSQL_DATABASE"' > "$output"
printf 'Backup saved: %s\n' "$output"
