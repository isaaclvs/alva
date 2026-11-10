require "test_helper"

module Api
  module V1
    # Test-only probe for the protected base; there is no sync endpoint yet.
    class PingController < BaseController
      def show
        render json: { household_id: current_household.id, device_id: device.id, scope: device.scope }
      end
    end
  end
end

class Api::V1::BaseControllerTest < ActionDispatch::IntegrationTest
  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @other_household = Household.create!(name: "Praia")
    @sync_device, @sync_token = AndroidDevice.issue(household: @home, name: "Sync", scope: AndroidDevice::SYNC)
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  test "a valid sync credential resolves its own household" do
    res = ping(headers: { "Authorization" => "Bearer #{@sync_token}" })

    assert_equal 200, res.status
    assert_equal "no-store", res.headers["Cache-Control"]
    body = JSON.parse(res.body)
    assert_equal @home.id, body["household_id"]
    assert_equal @sync_device.id, body["device_id"]
    assert_equal AndroidDevice::SYNC, body["scope"]
  end

  test "client ids never select the household" do
    other_device, = AndroidDevice.issue(household: @other_household, name: "Outro", scope: AndroidDevice::SYNC)

    res = ping(
      params: { household_id: @other_household.id, device_id: other_device.id, scope: "sync" },
      headers: { "Authorization" => "Bearer #{@sync_token}" }
    )

    assert_equal 200, res.status
    assert_equal @home.id, JSON.parse(res.body)["household_id"]
  end

  test "missing, empty, malformed and unknown credentials are unauthorized" do
    [
      {},
      { "Authorization" => "" },
      { "Authorization" => "Bearer " },
      { "Authorization" => "Bearer" },
      { "Authorization" => "bearer #{@sync_token}" },
      { "Authorization" => "Bearer  #{@sync_token}" },
      { "Authorization" => "Token #{@sync_token}" },
      { "Authorization" => "Bearer nao-existe" }
    ].each do |headers|
      res = ping(headers: headers)
      assert_equal 401, res.status, "expected 401 for #{headers.inspect}"
      assert_equal "unauthorized", JSON.parse(res.body)["error"]
      assert_equal "no-store", res.headers["Cache-Control"]
    end
  end

  test "revoked and rotated credentials are unauthorized" do
    @sync_device.revoke!
    assert_equal 401, ping(headers: { "Authorization" => "Bearer #{@sync_token}" }).status

    _, rotated_token = AndroidDevice.issue(household: @home, name: "Antigo", scope: AndroidDevice::SYNC)
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("outra-senha", cost: 4).to_s
    assert_equal 401, ping(headers: { "Authorization" => "Bearer #{rotated_token}" }).status
  end

  test "an add_only credential is forbidden and changes nothing" do
    _, add_only_token = AndroidDevice.issue(household: @home, name: "Widget")

    res = ping(headers: { "Authorization" => "Bearer #{add_only_token}" })

    assert_equal 403, res.status
    assert_equal "forbidden_scope", JSON.parse(res.body)["error"]
    assert_equal "no-store", res.headers["Cache-Control"]
    assert_nil @sync_device.reload.revoked_at
  end

  private

  # with_routing swaps the integration session for the block, so the response
  # must be captured before the original session is restored.
  def ping(params: {}, headers: {})
    with_routing do |set|
      set.draw { get "/api/v1/ping", to: "api/v1/ping#show" }
      get "/api/v1/ping", params: params, headers: headers
      integration_session.response
    end
  end
end
