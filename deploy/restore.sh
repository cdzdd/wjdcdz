#!/bin/sh
set -eu
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  printf 'Usage: sh deploy/restore.sh /absolute/path/to/backup.sql\n' >&2
  exit 1
fi
repo_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
docker compose --env-file "$repo_dir/.env" -f "$repo_dir/deploy/compose.yaml" stop server web
docker compose --env-file "$repo_dir/.env" -f "$repo_dir/deploy/compose.yaml" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --default-character-set=utf8mb4 "$MYSQL_DATABASE"' < "$1"
docker compose --env-file "$repo_dir/.env" -f "$repo_dir/deploy/compose.yaml" up -d server web
printf 'Restore completed.\n'
