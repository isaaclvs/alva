require "test_helper"

class Api::V1::DevicesControllerTest < ActionDispatch::IntegrationTest
  setup do
    @password = "house password"
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    @previous_host = ENV["ALVA_PUBLIC_HOST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create(@password, cost: 4).to_s
    ENV["ALVA_PUBLIC_HOST"] = "alva.example"
    ApiPairing::RATE_LIMIT_STORE.clear
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
    ENV["ALVA_PUBLIC_HOST"] = @previous_host
    ApiPairing::RATE_LIMIT_STORE.clear
  end

  test "enrolls a sync credential with the household password and returns the secret once" do
    assert_difference "AndroidDevice.count", 1 do
      post "/api/v1/devices", params: { password: @password, name: "Celular novo" }
    end

    assert_response :created
    assert_equal "no-store", response.headers["Cache-Control"]

    body = JSON.parse(response.body)
    assert_equal %w[device_id token], body.keys.sort

    device = AndroidDevice.find(body["device_id"])
    assert_equal AndroidDevice::SYNC, device.scope
    assert device.sync?
    assert_equal "Celular novo", device.name
    assert_equal AndroidDevice.digest(body["token"]), device.token_digest
    assert_not_equal body["token"], device.token_digest
  end

  test "rejects a wrong or missing password without creating a device" do
    assert_no_difference "AndroidDevice.count" do
      post "/api/v1/devices", params: { password: "errada", name: "Celular" }
    end
    assert_response :unauthorized
    assert_equal "unauthorized", JSON.parse(response.body)["error"]
    assert_not_includes response.body, "errada"
    assert_equal "no-store", response.headers["Cache-Control"]

    assert_no_difference "AndroidDevice.count" do
      post "/api/v1/devices", params: { name: "Celular" }
    end
    assert_response :unauthorized
  end

  test "an existing device token or a client scope does not replace the password" do
    _, add_only_token = AndroidDevice.issue(household: households(:home), name: "Widget")

    assert_no_difference "AndroidDevice.count" do
      post "/api/v1/devices", params: { name: "Celular", scope: "sync" },
        headers: { "Authorization" => "Bearer #{add_only_token}" }
    end
    assert_response :unauthorized
  end

  test "an access cookie does not replace the password" do
    cookies[HouseholdAccess::COOKIE] = HouseholdAccess.token

    assert_no_difference "AndroidDevice.count" do
      post "/api/v1/devices", params: { name: "Celular" }
    end
    assert_response :unauthorized
  end

  test "refuses sync enrollment without erroring when no password is configured" do
    ENV.delete("ALVA_PASSWORD_DIGEST")
    ENV.delete("ALVA_PUBLIC_HOST")

    assert_no_difference "AndroidDevice.count" do
      post "/api/v1/devices", params: { password: "qualquer" }
    end

    assert_response :forbidden
    assert_equal "sync_unavailable", JSON.parse(response.body)["error"]
    assert_equal "no-store", response.headers["Cache-Control"]
  end

  test "issued tokens are distinct and only the digest is stored" do
    post "/api/v1/devices", params: { password: @password, name: "A" }
    first = JSON.parse(response.body)
    post "/api/v1/devices", params: { password: @password, name: "B" }
    second = JSON.parse(response.body)

    assert_not_equal first["token"], second["token"]
    assert_equal 0, AndroidDevice.where(token_digest: [ first["token"], second["token"] ]).count
  end

  test "pairing parameters reach logging already filtered" do
    logged_params = nil
    subscription = ActiveSupport::Notifications.subscribe("start_processing.action_controller") do |*args|
      event = ActiveSupport::Notifications::Event.new(*args)
      logged_params = event.payload[:params] if event.payload[:controller] == "Api::V1::DevicesController"
    end

    post "/api/v1/devices", params: { password: @password, name: "Celular" }
    secret = JSON.parse(response.body).fetch("token")
    assert_response :created

    assert_not_nil logged_params, "expected the controller to emit its processing notification"
    assert_equal "[FILTERED]", logged_params["password"]
    assert_equal "Celular", logged_params["name"]
    assert_not_includes logged_params.values.join(" "), secret
  ensure
    ActiveSupport::Notifications.unsubscribe(subscription) if subscription
  end
end
