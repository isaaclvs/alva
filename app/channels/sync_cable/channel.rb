module SyncCable
  class Channel < ActionCable::Channel::Base
    def subscribed
      return reject unless connection.authorized_device?

      household = connection.android_device.household
      stream_from SyncInvalidation.stream_for(household), coder: ActiveSupport::JSON do |message|
        if connection.authorized_device?
          transmit(message)
        else
          connection.close(reason: "unauthorized", reconnect: false)
          stop_all_streams
        end
      end
    end
  end
end
