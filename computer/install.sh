#!/bin/sh
# Installs Weave's computer side for this user on Linux (Hyprland). No root.
#   curl -fsSL https://t3lluz.com/weave/install.sh | sh
# From a checkout (./install.sh) it runs the code where it lies, so a pull is an update. Piped
# from the web it fetches the code into ~/.local/share/weave. Either way it puts `weave` in
# ~/.local/bin, starts a user service, and shows the code to scan.
set -e
bin="$HOME/.local/bin"
data="${XDG_DATA_HOME:-$HOME/.local/share}"
units="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
src="${WEAVE_SRC:-https://github.com/T3lluz/Weave/archive/refs/heads/master.tar.gz}"

here=""
case "$0" in */install.sh) here=$(cd "$(dirname "$0")" && pwd) ;; esac
if [ -n "$here" ] && [ -f "$here/weave/__main__.py" ]; then
  code="$here"
else
  code="$data/weave/computer"
  tmp=$(mktemp -d)
  echo "fetching weave"
  curl -fsSL "$src" | tar -xz -C "$tmp"
  found=$(find "$tmp" -type f -path '*/weave/__main__.py' | head -n 1)
  [ -n "$found" ] || { echo "the download has no weave program in it"; exit 1; }
  rm -rf "$code"
  mkdir -p "$code"
  cp -r "$(dirname "$(dirname "$found")")"/. "$code"/
  rm -rf "$tmp"
fi

command -v python3 >/dev/null || { echo "weave needs python3"; exit 1; }
mkdir -p "$bin" "$data/icons/hicolor/scalable/apps" "$units"

# the path goes in single quotes, any quote in it closed and opened again: a home folder with a
# space, a quote or a dollar sign in its name must not change what the launcher runs
quoted=$(printf '%s' "$code" | sed "s/'/'\\\\''/g")
cat > "$bin/weave" <<LAUNCH
#!/bin/sh
WEAVE_CODE='$quoted'
PYTHONPATH="\$WEAVE_CODE\${PYTHONPATH:+:\$PYTHONPATH}" exec python3 -m weave "\$@"
LAUNCH
chmod +x "$bin/weave"
install -m644 "$code/share/weave.service" "$units/weave.service"
install -m644 "$code/share/weave.svg" "$data/icons/hicolor/scalable/apps/weave.svg"

[ -n "$HYPRLAND_INSTANCE_SIGNATURE" ] || command -v hyprctl >/dev/null || echo "note: weave reads the screens and pointer from Hyprland; other desktops aren't supported yet"
for tool in gpu-screen-recorder wtype wl-copy notify-send; do
  command -v "$tool" >/dev/null || echo "missing: $tool (the phone can't fully control this computer without it)"
done
[ -w /dev/uinput ] || echo "no write access to /dev/uinput: the phone can't move the mouse or type (add yourself to the input group, or a udev rule for uinput)"

systemctl --user daemon-reload
systemctl --user enable weave.service >/dev/null 2>&1 || true
systemctl --user restart weave.service
echo
echo "weave is running. Showing the code to scan:"
echo
sleep 1
"$bin/weave" pair || true
