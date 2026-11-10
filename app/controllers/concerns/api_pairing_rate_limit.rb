# Same 5 attempts / 15 minutes / IP budget for every pairing route, so the v1
# enrollment is never a second quota to bypass the widget limit.
module ApiPairingRateLimit
  extend ActiveSupport::Concern

  included do
    rate_limit to: ApiPairing::LIMIT, within: ApiPairing::WINDOW,
      scope: ApiPairing::RATE_LIMIT_SCOPE, store: ApiPairing::RATE_LIMIT_STORE,
      with: :render_pairing_rate_limited, only: :create
  end

  private

  def render_pairing_rate_limited
    response.headers["Retry-After"] = ApiPairing::WINDOW.to_i.to_s
    render json: { error: "rate_limited", retry_after: ApiPairing::WINDOW.to_i }, status: :too_many_requests
  end
end
