require "test_helper"

class SyncCable::ChannelTest < ActionCable::Channel::TestCase
  tests SyncCable::Channel

  setup do
    @device = Struct.new(:household).new(households(:home))
    stub_connection android_device: @device, authorized_device?: true
  end

  test "subscribes only to the authenticated household ignoring payload selectors" do
    subscribe household_id: "other", stream: "arbitrary"
    assert subscription.confirmed?
    assert_has_stream SyncInvalidation.stream_for(households(:home))
    assert_equal 1, subscription.streams.size
  end

  test "refuses expired credentials before streaming" do
    stub_connection android_device: @device, authorized_device?: false
    subscribe
    assert subscription.rejected?
    assert_no_streams
  end
  test "stream callback revalidates before delivery and closes after revocation or rotation" do
    old_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("teste", cost: 4).to_s
    device, secret = AndroidDevice.issue(household: households(:home), name: "Native", scope: AndroidDevice::SYNC)
    stub_connection android_device: device
    connection.define_singleton_method(:authorized_device?) do
      current = AndroidDevice.authenticate(secret)
      current&.sync? && current.id == device.id
    end
    closed = []
    connection.define_singleton_method(:close) { |**options| closed << options }
    subscribe
    callbacks = []
    # Channel::TestCase deliberately discards stream handlers. Capture and run
    # the actual subscribed block, rather than merely asserting a stream name.
    subscription.define_singleton_method(:stream_from) do |_stream, coder:, &handler|
      callbacks << handler
    end
    subscription.subscribed
    message = { "type" => "invalidate", "household_revision" => 2, "dictionary_revision" => 0 }
    callbacks.fetch(0).call(message)
    assert_equal [ message ], transmissions
    assert_empty closed

    device.revoke!
    callbacks.fetch(0).call(message)
    assert_equal [ message ], transmissions
    assert_equal [ { reason: "unauthorized", reconnect: false } ], closed
    assert_no_streams

    device.update!(revoked_at: nil)
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("outra", cost: 4).to_s
    callbacks.fetch(0).call(message)
    assert_equal [ message ], transmissions
    assert_equal 2, closed.size
  ensure
    ENV["ALVA_PASSWORD_DIGEST"] = old_digest
  end

end
