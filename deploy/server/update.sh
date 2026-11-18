#!/usr/bin/env bash
# Publish origin/master to t3lluz.com/weave: copy the site and the two
# install scripts into a new build, swap it in and restart the container.
#
# weave-deploy.timer runs this every minute; it does nothing unless master
# has moved. Run it by hand any time; --force rebuilds regardless.
#
# The whole script is one function, called on the last line, so bash has
# read all of it before `git reset` can rewrite this very file.
set -euo pipefail

main() {
  local home="${WEAVE_HOME:-$HOME/docker/weave}"
  local repo="$home/repo" build="$home/build" branch=master
  local force="${1:-}"
  local -a compose=(docker compose -f "$repo/deploy/server/compose.yml" --project-directory "$home")

  cd "$home"
  exec 9>"$home/.deploy.lock"
  flock -n 9 || exit 0

  git -C "$repo" fetch --quiet origin "$branch"
  local new sha current
  new=$(git -C "$repo" rev-parse "origin/$branch")
  sha=${new:0:7}
  current=$(readlink "$build/current" 2>/dev/null || true)
  if [[ "$current" == "site-$sha" && "$force" != "--force" ]]; then
    exit 0
  fi

  git -C "$repo" reset --quiet --hard "$new"
  git -C "$repo" clean --quiet -fd

  # The site as it is in the repo, with the computer side's installers beside
  # its pages: they are what `curl .../weave/install.sh` and
  # `irm .../weave/install.ps1` fetch.
  mkdir -p "$build"
  local stage="$build/.site-$sha.tmp"
  rm -rf "$stage" "$build/site-$sha"
  cp -a "$repo/site" "$stage"
  cp "$repo/computer/install.sh" "$repo/computer/install.ps1" "$stage/public/"
  echo "$new" >"$stage/version.txt"
  mv "$stage" "$build/site-$sha"
  ln -sfn "site-$sha" "$build/.current.tmp"
  mv -T "$build/.current.tmp" "$build/current"

  # Keep the three newest builds; older ones only take space.
  ls -1dt "$build"/site-* | tail -n +4 | xargs -r rm -rf

  # Recreates the container only if compose.yml changed. Then restart it
  # either way: its working directory is the build it started on.
  "${compose[@]}" up -d --remove-orphans --quiet-pull
  "${compose[@]}" restart weave

  echo "deployed $sha"
}

main "$@"
exit
