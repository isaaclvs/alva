class AndroidDevicesController < ActionController::API
  include ApiPairingRateLimit

  def create
    return render json: { error: "unauthorized" }, status: :unauthorized unless HouseholdAccess.enabled? && HouseholdAccess.authenticate(params[:password])

    device, secret = AndroidDevice.issue(household: Household.first!, name: params[:name], scope: AndroidDevice::ADD_ONLY)
    render json: { device_id: device.id, token: secret }, status: :created
  end
end
