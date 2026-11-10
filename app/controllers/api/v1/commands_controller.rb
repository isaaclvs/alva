module Api
  module V1
    # Runs a device's batch of commands (see SyncCommandProcessor). The household
    # and the identity come only from the bearer credential; `household_id`,
    # `device_id` and the like in the body are ignored.
    #
    # A well-formed batch is always 200 with one result per command, in order —
    # conflicts and rejections are results, not HTTP errors. Only the request as
    # a whole can fail: authentication (BaseController), an envelope that is not
    # one (422 malformed_command) or an unexpected error (500 internal_error;
    # what was already committed stays and a resend answers `duplicate`).
    class CommandsController < BaseController
      MAX_COMMANDS = 50
      # Results that mean the phone's picture of the list is stale.
      STALE_ERRORS = %w[precondition_failed gone not_found].freeze

      wrap_parameters false
      rescue_from ActionDispatch::Http::Parameters::ParseError do
        render_api_error("malformed_command", :unprocessable_content)
      end

      def create
        body = request.request_parameters
        commands = body["commands"]
        observed = body["observed_revision"]
        unless commands.is_a?(Array) && commands.size.between?(1, MAX_COMMANDS) && valid_revision?(observed)
          return render_api_error("malformed_command", :unprocessable_content)
        end

        results = SyncCommandProcessor.new(device:).call(commands, observed_revision: observed)
        render json: { revision: Household.where(id: current_household.id).pick(:sync_revision), results:,
          snapshot_required: results.any? { |result| stale?(result) } }
      end

      private

      def valid_revision?(value)
        value.nil? || (value.is_a?(Integer) && value >= 0)
      end

      def stale?(result)
        result[:status] == "conflict" || (result[:status] == "rejected" && result[:error].in?(STALE_ERRORS))
      end
    end
  end
end
