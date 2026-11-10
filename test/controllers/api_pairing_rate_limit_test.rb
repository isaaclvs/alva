require "test_helper"

class ApiPairingRateLimitTest < ActionDispatch::IntegrationTest
  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    @previous_host = ENV["ALVA_PUBLIC_HOST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("house password", cost: 4).to_s
    ENV["ALVA_PUBLIC_HOST"] = "alva.example"
    ApiPairing::RATE_LIMIT_STORE.clear
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
    ENV["ALVA_PUBLIC_HOST"] = @previous_host
    ApiPairing::RATE_LIMIT_STORE.clear
  end

  test "the legacy and v1 routes share one 5 per 15 minutes budget" do
    5.times do
      post "/api/android/devices", params: { password: "errada" }, env: { "REMOTE_ADDR" => "203.0.113.9" }
      assert_response :unauthorized
    end

    post "/api/v1/devices", params: { password: "errada" }, env: { "REMOTE_ADDR" => "203.0.113.9" }

    assert_response :too_many_requests
    assert_equal "rate_limited", JSON.parse(response.body)["error"]
    assert_equal ApiPairing::WINDOW.to_i.to_s, response.headers["Retry-After"]
  end

  test "the quota resets after the window" do
    6.times { post "/api/v1/devices", params: { password: "errada" }, env: { "REMOTE_ADDR" => "203.0.113.10" } }
    assert_response :too_many_requests

    travel ApiPairing::WINDOW + 1.second do
      post "/api/v1/devices", params: { password: "errada" }, env: { "REMOTE_ADDR" => "203.0.113.10" }
      assert_response :unauthorized
    end
  end

  test "each IP keeps its own budget" do
    6.times { post "/api/v1/devices", params: { password: "errada" }, env: { "REMOTE_ADDR" => "203.0.113.11" } }
    assert_response :too_many_requests

    post "/api/v1/devices", params: { password: "errada" }, env: { "REMOTE_ADDR" => "203.0.113.12" }
    assert_response :unauthorized
  end
end
