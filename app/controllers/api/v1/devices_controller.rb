module Api
  module V1
    # Explicit sync enrollment: the household password buys a `sync` credential,
    # returned only in this response. Cookies and existing device tokens cannot
    # replace the password, and a client-sent scope is ignored.
    class DevicesController < ActionController::API
      include ApiErrorResponses
      include ApiPairingRateLimit

      def create
        response.headers["Cache-Control"] = "no-store"
        return render_api_error("sync_unavailable", :forbidden) unless HouseholdAccess.enabled?
        return render_api_error("unauthorized", :unauthorized) unless HouseholdAccess.authenticate(params[:password])

        device, secret = AndroidDevice.issue(household: Household.first!, name: params[:name], scope: AndroidDevice::SYNC)
        render json: { device_id: device.id, token: secret }, status: :created
      end
    end
  end
end
