#!/usr/bin/env bash
# Make t3lluz.com/weave public (any case: /Weave, /WEAVE, ...).
#
# t3lluz.com goes public through Cinema Info's Cloudflare tunnel
# "t3lluz-public", whose ingress sends each app's path to its container and
# 404s the rest. This adds one rule in front of that 404:
# /weave* -> weave:8080. The container sits on the tunnel's network
# (cinema-info_edge) for that. The site has no admin side: all of it is public.
#
# It reads the tunnel's current ingress and only swaps its own rule, so the
# other routes (Cinema Info, YTMQ, the portfolio, ...) stay as they are. Safe
# to run again. `--remove` takes the route out.
#
# Needs a Cloudflare API token with Account/Cloudflare Tunnel/Edit:
# CF_API_TOKEN, or else the CLOUDFLARE_API_TOKEN Caddy uses.
set -euo pipefail

main() {
  local zone_name="t3lluz.com" tunnel_name="t3lluz-public"
  local service="http://weave:8080" path='(?i)^/weave(/.*)?$'
  local token="${CF_API_TOKEN:-}"
  if [[ -z "$token" && -f "$HOME/docker/caddy/.env" ]]; then
    token=$(sed -n 's/^CLOUDFLARE_API_TOKEN=//p' "$HOME/docker/caddy/.env" | tr -d '"'"'"'')
  fi
  [[ -n "$token" ]] || die "no Cloudflare API token (set CF_API_TOKEN)"

  cf() { # method path [json]
    local out
    out=$(curl -sS -X "$1" "https://api.cloudflare.com/client/v4$2" \
      -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
      ${3:+--data "$3"})
    if [[ $(jq -r .success <<<"$out") != "true" ]]; then
      echo "Cloudflare: $1 $2 failed: $(jq -c .errors <<<"$out")" >&2
      return 1
    fi
    printf '%s' "$out"
  }

  local zone account tunnel
  zone=$(cf GET "/zones?name=$zone_name" | jq -r '.result[0].id')
  account=$(cf GET "/zones/$zone" | jq -r '.result.account.id')
  tunnel=$(cf GET "/accounts/$account/cfd_tunnel?name=$tunnel_name&is_deleted=false" | jq -r '.result[0].id // empty')
  [[ -n "$tunnel" ]] || die "tunnel $tunnel_name not found; run Cinema Info's go-public.sh first"

  local config
  config=$(cf GET "/accounts/$account/cfd_tunnel/$tunnel/configurations" | jq -c '.result.config')

  # Drop our old rule, then (unless removing) put it back before the last
  # rule, which is the catch-all 404.
  local updated
  updated=$(jq -c --arg host "$zone_name" --arg path "$path" --arg svc "$service" --arg remove "${1:-}" '
      .ingress |= (
        map(select(.service != $svc)) as $rest
        | if $remove == "--remove" then $rest
          else $rest[:-1] + [{hostname: $host, path: $path, service: $svc}] + $rest[-1:]
          end)' <<<"$config")
  cf PUT "/accounts/$account/cfd_tunnel/$tunnel/configurations" "{\"config\":$updated}" >/dev/null
  jq -r '.ingress[] | "  \(.hostname // "*")\(.path // "") -> \(.service)"' <<<"$updated"

  [[ "${1:-}" == "--remove" ]] && { echo "removed $zone_name/weave"; return; }

  # Ask from outside, through a Cloudflare edge address (this host does not
  # resolve t3lluz.com publicly; see Cinema Info's README).
  local edge code="" i
  edge=$(dig +short "$zone_name" @1.1.1.1 | grep -v '^100\.' | head -1 || true)
  [[ -n "$edge" ]] || die "no public address for $zone_name"
  for i in $(seq 1 20); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --resolve "$zone_name:443:$edge" \
      "https://$zone_name/Portfolio/api/health" -L || true)
    [[ "$code" == "200" ]] && break
    sleep 3
  done
  [[ "$code" == "200" ]] || die "public check failed (edge $edge, HTTP ${code:-none})"
  code=$(curl -s -o /dev/null -w '%{http_code}' --resolve "$zone_name:443:$edge" \
    "https://$zone_name/Portfolio/Admin" -L || true)
  [[ "$code" == "404" ]] || die "admin is reachable from outside (HTTP $code)"
  echo "public: https://$zone_name/portfolio answers from $edge; /portfolio/admin is tailnet only"
}

die() {
  echo "go-public: $*" >&2
  exit 1
}

main "$@"
exit
