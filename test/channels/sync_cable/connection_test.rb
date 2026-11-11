require "test_helper"

class SyncCable::ConnectionTest < ActionCable::Connection::TestCase
  tests SyncCable::Connection

  setup do
    @old_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("teste", cost: 4).to_s
    @device, @secret = AndroidDevice.issue(household: households(:home), name: "Native", scope: AndroidDevice::SYNC)
  end
  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @old_digest }

  test "sync bearer authenticates and cannot subscribe to Turbo" do
    connect headers: { "Authorization" => "Bearer #{@secret}" }
    assert_equal @device, connection.android_device
    assert connection.authorized_device?
    frames = []
    connection.define_singleton_method(:transmit) { |frame| frames << frame }
    connection.handle_channel_command("command" => "subscribe", "identifier" => '{"channel":"Turbo::StreamsChannel"}')
    assert_equal "reject_subscription", frames.first[:type]
    assert_nil connection.subscriptions # Connection::TestCase does not open subscriptions.
  end

  test "anonymous cookie only add_only and revoked clients are rejected" do
    assert_reject_connection { connect }
    cookies.encrypted[HouseholdAccess::COOKIE] = HouseholdAccess.token
    assert_reject_connection { connect }
    _, add_only = AndroidDevice.issue(household: households(:home), name: "Widget")
    assert_reject_connection { connect headers: { "Authorization" => "Bearer #{add_only}" } }
    @device.revoke!
    assert_reject_connection { connect headers: { "Authorization" => "Bearer #{@secret}" } }
  end

  test "rotation and revocation invalidate an already connected device" do
    connect headers: { "Authorization" => "Bearer #{@secret}" }
    @device.revoke!
    assert_not connection.authorized_device?
    @device.update!(revoked_at: nil)
    assert connection.authorized_device?
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("nova", cost: 4).to_s
    assert_not connection.authorized_device?
  end

  test "native endpoint uses its isolated connection and the shared adapter" do
    server = SyncCable::Server.instance
    assert_equal SyncCable::Connection, server.config.connection_class.call
    assert_same ActionCable.server.pubsub, server.pubsub
    assert_not_equal server.config.connection_class.call, ActionCable.server.config.connection_class.call
  end
end
