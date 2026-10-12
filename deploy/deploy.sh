#!/bin/sh
# Pulls and (re)starts the release named in release.env, with the settings in .env.
# Also used by hand: restart, or roll back by putting an older IMAGE_TAG in release.env.
set -eu
cd "$(dirname "$0")"
compose() {
    docker compose --env-file .env --env-file release.env "$@"
}
compose pull
compose up -d --remove-orphans
docker image prune -f
