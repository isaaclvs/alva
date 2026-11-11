module SyncCable
  class Server < ActionCable::Server::Base
    # Share the adapter with web Cable, including the in-process development
    # adapter, so committed Rails changes reach both endpoints.
    def pubsub = ActionCable.server.pubsub

    def self.instance
      @instance ||= begin
        config = ActionCable.server.config.dup
        config.connection_class = -> { SyncCable::Connection }
        new(config: config)
      end
    end
  end
end
