class QuickAddController < ActionController::API
  # The widget's panel sends by hand, one press at a time, so this is far above
  # normal use: it only stops a broken or hostile client from turning one token
  # into an unbounded stream of writes. Per device, in a process-local store
  # like the pairing budget (single Puma process; a restart clears it).
  RATE_LIMIT = 60
  RATE_LIMIT_WINDOW = 1.minute
  RATE_LIMIT_STORE = ActiveSupport::Cache::MemoryStore.new

  before_action :authenticate_device!
  # Authenticated calls are budgeted per token (the device), like the pairing
  # budget is per IP: one widget cannot spend another's, and a request that was
  # already refused has no token to spend.
  rate_limit to: RATE_LIMIT, within: RATE_LIMIT_WINDOW, by: -> { @device.id },
    scope: :quick_add, store: RATE_LIMIT_STORE, with: :render_rate_limited, only: :create

  def create
    names = params[:name].to_s
    return render json: { error: "invalid_name" }, status: :unprocessable_entity if ItemNameSplitter.call(names).empty?

    results = @device.household.add_items(names)
    render json: { results: results.map { |result| { name: result.item.name, outcome: result.outcome } } }
  end

  private

  def authenticate_device!
    @device = AndroidDevice.authenticate(request.authorization.to_s.delete_prefix("Bearer "))
    render json: { error: "unauthorized" }, status: :unauthorized unless @device
  end

  def render_rate_limited
    response.headers["Retry-After"] = RATE_LIMIT_WINDOW.to_i.to_s
    render json: { error: "rate_limited", retry_after: RATE_LIMIT_WINDOW.to_i }, status: :too_many_requests
  end
end
