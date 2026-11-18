# The Weave website on twolluz

t3lluz.com/weave is `site/` of this repo, served by a small Node server with no dependencies. It has the landing page, the pairing page a scanned code opens (`/weave/pair`), the two installers of the computer side (`/weave/install.sh`, `/weave/install.ps1`, copied from `computer/` at each deploy) and `/weave/apk`, which redirects to the newest release's APK.

## Layout on the server

```
~/docker/weave/
  repo/     deploy checkout of master. Never work in it.
  build/    site-<sha> folders and the `current` link the container runs
~/projects/weave   the work clone
```

No secrets and no state: there is no `.env` and no `data/`.

## Deploys

`update.sh` runs every minute from `weave-deploy.timer`. It does nothing unless `origin/master` moved. Then it copies `site/` and the installers into `build/site-<sha>`, swaps `current`, keeps three builds and restarts the container.

```bash
systemctl --user enable --now weave-deploy.timer
~/docker/weave/repo/deploy/server/update.sh --force    # by hand
```

## Routes

- **Tailnet:** `handle /weave* { reverse_proxy weave:8080 }` in the apex block of `~/docker/caddy/Caddyfile`. The site redirects other casings to `/weave`.
- **Public:** `go-public.sh` adds `/weave*` to the shared Cloudflare tunnel in front of its catch-all 404, and checks it from outside. `go-public.sh --remove` takes it out. All of the site is public; it has no admin side.

## First setup

```bash
mkdir -p ~/docker/weave && cd ~/docker/weave
git clone https://github.com/T3lluz/Weave.git repo
install -m644 repo/deploy/server/systemd/weave-deploy.* ~/.config/systemd/user/
systemctl --user daemon-reload
repo/deploy/server/update.sh --force
systemctl --user enable --now weave-deploy.timer
```

Then the Caddy route (back the file up, validate, reload) and `go-public.sh`.
