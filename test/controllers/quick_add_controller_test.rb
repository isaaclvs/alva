require "test_helper"

class QuickAddControllerTest < ActionDispatch::IntegrationTest
  setup do
    @password = "house password"
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create(@password)
    ENV["ALVA_PUBLIC_HOST"] = "alva.example"
    ApiPairing::RATE_LIMIT_STORE.clear
    QuickAddController::RATE_LIMIT_STORE.clear
  end

  teardown do
    ENV.delete("ALVA_PASSWORD_DIGEST")
    ENV.delete("ALVA_PUBLIC_HOST")
    ApiPairing::RATE_LIMIT_STORE.clear
    QuickAddController::RATE_LIMIT_STORE.clear
  end

  test "enrolls a device with the household password and only returns a one-time token" do
    post "/api/android/devices", params: { password: @password, name: "Wife's phone" }

    assert_response :created
    token = JSON.parse(response.body).fetch("token")
    device = AndroidDevice.last
    assert_equal "Wife's phone", device.name
    assert_equal AndroidDevice.digest(token), device.token_digest
    assert_not_equal token, device.token_digest
  end

  test "quick add requires a device token and adds through household rules" do
    _, token = AndroidDevice.issue(household: households(:home), name: "Phone")

    assert_difference "ListItem.count", 1 do
      post "/api/quick_add", params: { name: "Banana" }, headers: { "Authorization" => "Bearer #{token}" }
    end

    assert_response :success
    assert_equal "created", JSON.parse(response.body).dig("results", 0, "outcome")
    assert_equal categories(:produce), ListItem.last.category
  end

  test "quick add rejects revoked and invalid credentials" do
    device, token = AndroidDevice.issue(household: households(:home), name: "Phone")
    device.update!(revoked_at: Time.current)

    post "/api/quick_add", params: { name: "Banana" }, headers: { "Authorization" => "Bearer #{token}" }
    assert_response :unauthorized
  end

  test "changing the household password invalidates enrolled device tokens" do
    _, token = AndroidDevice.issue(household: households(:home), name: "Phone")
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("rotated password")

    post "/api/quick_add", params: { name: "Banana" }, headers: { "Authorization" => "Bearer #{token}" }

    assert_response :unauthorized
  end

  test "legacy enrollment always emits add_only even when a scope is sent" do
    post "/api/android/devices", params: { password: @password, name: "Widget", scope: "sync" }

    assert_response :created
    assert_equal AndroidDevice::ADD_ONLY, AndroidDevice.last.scope

    post "/api/android/devices", params: { password: @password, name: "Widget 2" }
    token = JSON.parse(response.body).fetch("token")
    assert_difference "ListItem.count", 1 do
      post "/api/quick_add", params: { name: "Banana" }, headers: { "Authorization" => "Bearer #{token}" }
    end
    assert_response :success
  end

  test "a sync credential can also add through quick_add" do
    _, token = AndroidDevice.issue(household: households(:home), name: "Sync", scope: AndroidDevice::SYNC)

    assert_difference "ListItem.count", 1 do
      post "/api/quick_add", params: { name: "Banana" }, headers: { "Authorization" => "Bearer #{token}" }
    end
    assert_response :success
  end

  test "quick add cuts a pasted name at the model's limit" do
    _, token = AndroidDevice.issue(household: households(:home), name: "Phone")
    name = "x" * (ListItem::MAX_NAME_LENGTH + 1)

    assert_difference "ListItem.count", 1 do
      post "/api/quick_add", params: { name: name }, headers: { "Authorization" => "Bearer #{token}" }
    end

    assert_response :success
    assert_equal "x" * ListItem::MAX_NAME_LENGTH, ListItem.order(:id).last.name
    assert_equal "created", JSON.parse(response.body).dig("results", 0, "outcome")
  end

  test "quick add is budgeted per token" do
    _, token = AndroidDevice.issue(household: households(:home), name: "Phone")
    _, other = AndroidDevice.issue(household: households(:home), name: "Other")

    QuickAddController::RATE_LIMIT.times do
      post "/api/quick_add", params: { name: "Banana" }, headers: { "Authorization" => "Bearer #{token}" }
      assert_response :success
    end

    travel QuickAddController::RATE_LIMIT_WINDOW + 1.second do
      post "/api/quick_add", params: { name: "Banana" }, headers: { "Authorization" => "Bearer #{token}" }
      assert_response :success
    end

    # The other token keeps its own budget: the limit is per credential.
    post "/api/quick_add", params: { name: "Banana" }, headers: { "Authorization" => "Bearer #{other}" }
    assert_response :success
  end

  test "quick add refuses a token over its budget with 429 and Retry-After" do
    _, token = AndroidDevice.issue(household: households(:home), name: "Phone")
    (QuickAddController::RATE_LIMIT + 1).times do
      post "/api/quick_add", params: { name: "Banana" }, headers: { "Authorization" => "Bearer #{token}" }
    end

    assert_response :too_many_requests
    assert_equal "rate_limited", JSON.parse(response.body)["error"]
    assert_equal QuickAddController::RATE_LIMIT_WINDOW.to_i.to_s, response.headers["Retry-After"]
  end

  test "quick add refuses an old token without a 500 once the password is gone" do
    _, token = AndroidDevice.issue(household: households(:home), name: "Phone")
    ENV.delete("ALVA_PASSWORD_DIGEST")
    ENV.delete("ALVA_PUBLIC_HOST")

    assert_no_difference "ListItem.count" do
      post "/api/quick_add", params: { name: "Banana" }, headers: { "Authorization" => "Bearer #{token}" }
    end
    assert_response :unauthorized
  end
end
