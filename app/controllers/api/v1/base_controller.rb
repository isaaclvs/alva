module Api
  module V1
    # Protected base for sync endpoints. The household comes from the bearer
    # credential; ids sent by the client never select it. Only `sync`
    # credentials pass: the widget's add_only token gets 403 here.
    class BaseController < ActionController::API
      include ApiErrorResponses

      BEARER = /\ABearer (?<secret>[A-Za-z0-9_-]+)\z/

      before_action :authenticate_device!
      rescue_from StandardError, with: :render_internal_error

      attr_reader :device, :current_household

      private

      def render_internal_error(error)
        # Preserve Rails' status handling for known HTTP errors (404, 400, etc.).
        raise error unless ActionDispatch::ExceptionWrapper.status_code_for_exception(error.class.name) == 500

        Rails.error.report(error, handled: true, context: { request_id: request.request_id })
        render_api_error("internal_error", :internal_server_error)
      end

      def authenticate_device!
        response.headers["Cache-Control"] = "no-store"
        @device = AndroidDevice.authenticate(bearer_secret)
        return render_api_error("unauthorized", :unauthorized) unless @device
        return render_api_error("forbidden_scope", :forbidden) unless @device.sync?

        @current_household = @device.household
      end

      def bearer_secret
        match = BEARER.match(request.authorization.to_s)
        match && match[:secret]
      end
    end
  end
end
