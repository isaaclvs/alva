# Shared by script/android_rails_transport_check and script/android_rails_sync_check (source it, do not run it).
#
# harness_up starts an isolated REAL Rails and describes it in $work/harness.json:
#   1. RAILS_ENV=test with a throwaway SQLite file in a temp directory (DATABASE_URL), loaded from
#      db/schema.rb and test/fixtures. Never storage/*.sqlite3, never development or production data,
#      never a real household.
#   2. Issues sync (two devices) / add_only / revoked credentials for the fixture household.
#   3. Starts `bin/rails server` bound to 127.0.0.1 on a free port.
# The caller gets $root, $work, $port and a cleanup trap that stops Rails and removes $work.

harness_up() {
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  cd "$root"

  work="$(mktemp -d "${TMPDIR:-/tmp}/alva-rails-harness.XXXXXX")"
  server_pid=""
  harness_cleanup() {
    if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
      kill "$server_pid" 2>/dev/null || true
      wait "$server_pid" 2>/dev/null || true
    fi
    rm -rf "$work"
  }
  trap harness_cleanup EXIT

  # Nothing from the surrounding shell may point this at real data.
  unset ALVA_PUBLIC_HOST SECRET_KEY_BASE RAILS_MASTER_KEY
  export RAILS_ENV=test
  export DATABASE_URL="sqlite3:$work/harness.sqlite3"
  export RAILS_LOG_LEVEL="${RAILS_LOG_LEVEL:-warn}"
  if [[ "$DATABASE_URL" != "sqlite3:$work/"* || "$work" == "$root"* ]]; then
    echo "refusing: the database must live in a temp directory" >&2
    exit 1
  fi

  echo "== preparing an isolated test database in $work"
  bin/rails db:schema:load >/dev/null
  bin/rails db:fixtures:load >/dev/null

  # The password digest is what ties a credential to the household; any digest will do here.
  ALVA_PASSWORD_DIGEST="$(bin/rails runner 'print BCrypt::Password.create("harness-only", cost: 4)')"
  export ALVA_PASSWORD_DIGEST

  port="$(ruby -rsocket -e 'server = TCPServer.new("127.0.0.1", 0); print server.addr[1]; server.close')"

  bin/rails runner '
    home = Household.find_by!(public_id: "10000000-0000-4000-8000-000000000001")
    _, sync = AndroidDevice.issue(household: home, name: "Harness sync", scope: AndroidDevice::SYNC)
    _, sync_b = AndroidDevice.issue(household: home, name: "Harness sync B", scope: AndroidDevice::SYNC)
    _, widget = AndroidDevice.issue(household: home, name: "Harness widget", scope: AndroidDevice::ADD_ONLY)
    gone, revoked = AndroidDevice.issue(household: home, name: "Harness revoked", scope: AndroidDevice::SYNC)
    gone.revoke!
    puts JSON.generate(
      url: "http://127.0.0.1:'"$port"'", sync_token: sync, sync_token_b: sync_b, add_only_token: widget,
      revoked_token: revoked, household_public_id: home.public_id
    )
  ' >"$work/harness.json"

  echo "== starting Rails (RAILS_ENV=test) on 127.0.0.1:$port"
  bin/rails server -b 127.0.0.1 -p "$port" -P "$work/server.pid" >"$work/server.log" 2>&1 &
  server_pid=$!
  local ready=""
  for _ in $(seq 1 120); do
    if curl -fsS -o /dev/null "http://127.0.0.1:$port/up" 2>/dev/null; then ready=1; break; fi
    if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
    sleep 0.5
  done
  if [[ -z "$ready" ]]; then
    echo "Rails did not come up; its log:" >&2
    tail -n 40 "$work/server.log" >&2
    exit 1
  fi
}

# harness_require_run REPORT_XML NAME: fails unless the test class ran, with nothing skipped, failed or errored.
harness_require_run() {
  local report="$1" name="$2"
  if [[ ! -f "$report" ]]; then
    echo "no test report: $name did not run" >&2
    exit 1
  fi
  local summary
  summary="$(grep -o '<testsuite [^>]*' "$report" | head -n1)"
  echo "== $summary"
  if ! grep -q 'skipped="0"' <<<"$summary" || ! grep -q 'failures="0"' <<<"$summary" || ! grep -q 'errors="0"' <<<"$summary" \
     || grep -q 'tests="0"' <<<"$summary"; then
    echo "$name was skipped or failed; nothing was proven" >&2
    exit 1
  fi
}
