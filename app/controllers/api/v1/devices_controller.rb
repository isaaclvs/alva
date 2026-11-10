module Api
  module V1
    # Explicit sync enrollment: the household password buys a `sync` credential,
    # returned only in this response. Cookies and existing device tokens cannot
    # replace the password, and a client-sent scope is ignored.
    #
    # Authorizing the same phone again passes the secret it held before as
    # `previous_token`. That is the only way to continue the old credential's
    # lineage, so commands it already sent resolve instead of being duplicated;
    # a device id or lineage sent by the client never grants anything.
    class DevicesController < ActionController::API
      include ApiErrorResponses
      include ApiPairingRateLimit

      def create
        response.headers["Cache-Control"] = "no-store"
        return render_api_error("sync_unavailable", :forbidden) unless HouseholdAccess.enabled?
        return render_api_error("unauthorized", :unauthorized) unless HouseholdAccess.authenticate(params[:password])

        household = Household.first!
        predecessor = AndroidDevice.predecessor_for(household, params[:previous_token])
        return render_api_error("invalid_previous_token", :unprocessable_content) if params[:previous_token].present? && !predecessor

        device, secret = AndroidDevice.issue(household:, name: params[:name], scope: AndroidDevice::SYNC, predecessor:)
        render json: { device_id: device.id, token: secret }, status: :created
      end
    end
  end
end
