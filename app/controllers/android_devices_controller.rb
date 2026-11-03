class AndroidDevicesController < ActionController::API
  LOGIN_STORE = ActiveSupport::Cache::MemoryStore.new
  rate_limit to: 5, within: 15.minutes, only: :create, store: LOGIN_STORE

  def create
    return render json: { error: "unauthorized" }, status: :unauthorized unless HouseholdAccess.enabled? && HouseholdAccess.authenticate(params[:password])

    device, secret = AndroidDevice.issue(household: Household.first!, name: params[:name])
    render json: { device_id: device.id, token: secret }, status: :created
  end
end
