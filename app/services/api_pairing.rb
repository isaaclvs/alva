# Shared pairing budget for the widget and the sync enrollment route.
#
# Both routes use one process-local store, so alternating between them cannot
# buy extra attempts. The Atlas deployment runs a single Puma process (no
# WEB_CONCURRENCY); with several workers each process would keep its own
# budget, like SessionsController's login limit. Restarting the server clears
# the counters. The budget is per IP, resolved by Rails' trusted-proxy rules.
module ApiPairing
  LIMIT = 5
  WINDOW = 15.minutes
  RATE_LIMIT_SCOPE = :api_pairing
  RATE_LIMIT_STORE = ActiveSupport::Cache::MemoryStore.new
end
