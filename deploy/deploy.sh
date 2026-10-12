#!/bin/sh
# Pulls and (re)starts the release named in release.env, with the settings in .env.
# Also used by hand: restart, or roll back by putting an older IMAGE_TAG in release.env.
set -eu
cd "$(dirname "$0")"
. ./release.env
compose() {
    docker compose --env-file .env --env-file release.env "$@"
}
compose pull
compose up -d --remove-orphans
# Every release is a new ~200 MB image: keep only the running one. A rollback pulls its
# image again from Docker Hub. Images of other projects on the server are left alone.
docker image ls --filter "reference=$DOCKERHUB_IMAGE" --format '{{.Repository}}:{{.Tag}}' \
    | grep -vx "$DOCKERHUB_IMAGE:$IMAGE_TAG" \
    | xargs -r docker image rm
docker image prune -f
