module SyncCable
  # Separate from cookie-authenticated Turbo. A native credential can subscribe
  # only to the revision channel, never to arbitrary signed Turbo streams.
  class Connection < ActionCable::Connection::Base
    BEARER = /\ABearer (?<secret>[A-Za-z0-9_-]+)\z/
    identified_by :android_device

    def connect
      @sync_secret = BEARER.match(request.headers["HTTP_AUTHORIZATION"].to_s)&.[](:secret)
      self.android_device = AndroidDevice.authenticate(@sync_secret)
      reject_unauthorized_connection unless android_device&.sync?
    end

    def authorized_device?
      current = AndroidDevice.authenticate(@sync_secret)
      current&.sync? && current.id == android_device.id
    end

    def handle_channel_command(payload)
      unless authorized_device?
        close(reason: "unauthorized", reconnect: false)
        return
      end
      identifier = ActiveSupport::JSON.decode(payload["identifier"].to_s)
      unless identifier.is_a?(Hash) && identifier["channel"] == "SyncCable::Channel"
        transmit(type: "reject_subscription", identifier: payload["identifier"])
        return
      end
      super
    rescue JSON::ParserError
      close(reason: "invalid_request", reconnect: false)
    end
  end
end
