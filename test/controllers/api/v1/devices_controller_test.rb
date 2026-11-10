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

  test "pairing with the previous secret continues the lineage of the phone" do
    post "/api/v1/devices", params: { password: @password, name: "Celular" }
    first = JSON.parse(response.body)
    AndroidDevice.find(first["device_id"]).revoke!

    assert_difference "AndroidDevice.count", 1 do
      post "/api/v1/devices", params: { password: @password, name: "Celular", previous_token: first["token"] }
    end

    assert_response :created
    second = JSON.parse(response.body)
    assert_equal %w[device_id token], second.keys.sort
    assert_not_equal first["device_id"], second["device_id"]
    assert_equal AndroidDevice.find(first["device_id"]).lineage_id, AndroidDevice.find(second["device_id"]).lineage_id
  end

  test "a previous secret after a password rotation still continues the lineage" do
    post "/api/v1/devices", params: { password: @password, name: "Celular" }
    first = JSON.parse(response.body)
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha nova", cost: 4).to_s

    post "/api/v1/devices", params: { password: "senha nova", name: "Celular", previous_token: first["token"] }

    assert_response :created
    second = JSON.parse(response.body)
    assert_equal AndroidDevice.find(first["device_id"]).lineage_id, AndroidDevice.find(second["device_id"]).lineage_id
  end

  test "pairing without a previous secret starts a new lineage and never trusts a client-sent one" do
    post "/api/v1/devices", params: { password: @password, name: "Ana" }
    ana = AndroidDevice.find(JSON.parse(response.body)["device_id"])

    post "/api/v1/devices", params: { password: @password, name: "Isaac", lineage_id: ana.lineage_id, device_id: ana.id }

    assert_response :created
    isaac = AndroidDevice.find(JSON.parse(response.body)["device_id"])
    assert_not_equal ana.lineage_id, isaac.lineage_id
  end

  test "an unknown, widget or foreign previous secret is refused without creating a device" do
    home = households(:home)
    _, widget_secret = AndroidDevice.issue(household: home, name: "Widget")
    _, foreign_secret = AndroidDevice.issue(household: Household.create!(name: "Praia"), name: "Outro", scope: AndroidDevice::SYNC)

    [ "nao-existe", widget_secret, foreign_secret ].each do |previous|
      assert_no_difference "AndroidDevice.count" do
        post "/api/v1/devices", params: { password: @password, name: "Celular", previous_token: previous }
      end
      assert_response :unprocessable_content
      assert_equal "invalid_previous_token", JSON.parse(response.body)["error"]
      assert_not_includes response.body, previous
    end
  end

  test "a previous secret does not replace the password" do
    post "/api/v1/devices", params: { password: @password, name: "Celular" }
    first = JSON.parse(response.body)

    assert_no_difference "AndroidDevice.count" do
      post "/api/v1/devices", params: { password: "errada", name: "Celular", previous_token: first["token"] }
    end

    assert_response :unauthorized
  end

  test "the previous secret reaches logging filtered" do
    logged_params = nil
    subscription = ActiveSupport::Notifications.subscribe("start_processing.action_controller") do |*args|
      event = ActiveSupport::Notifications::Event.new(*args)
      logged_params = event.payload[:params] if event.payload[:controller] == "Api::V1::DevicesController"
    end

    post "/api/v1/devices", params: { password: @password, previous_token: "segredo-anterior" }

    assert_equal "[FILTERED]", logged_params["previous_token"]
  ensure
    ActiveSupport::Notifications.unsubscribe(subscription) if subscription
  end
end
