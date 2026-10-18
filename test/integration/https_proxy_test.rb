require "test_helper"

# On the Atlas the app is reached at https://atlas.<tailnet>.ts.net through
# `tailscale serve`, which proxies to Puma over plain HTTP and sends
# X-Forwarded-Proto: https. Rails must treat those requests as HTTPS, or the
# CSRF origin check rejects every form (adding, ticking, removing items).
class HttpsProxyTest < ActionDispatch::IntegrationTest
  HOST = "atlas.example.ts.net".freeze
  PROXY_HEADERS = { "X-Forwarded-Proto" => "https", "X-Forwarded-Host" => HOST }.freeze

  setup do
    @forgery_protection = ActionController::Base.allow_forgery_protection
    ActionController::Base.allow_forgery_protection = true
    host! HOST
  end

  teardown { ActionController::Base.allow_forgery_protection = @forgery_protection }

  test "a form posted from the https origin through the proxy is accepted" do
    get root_path, headers: PROXY_HEADERS
    assert_response :success
    token = css_select("meta[name=csrf-token]").first["content"]

    assert_difference -> { ListItem.count } do
      post list_items_path, params: { list_item: { name: "Banana" }, authenticity_token: token },
        headers: PROXY_HEADERS.merge("Origin" => "https://#{HOST}", "Accept" => "text/vnd.turbo-stream.html")
    end
    assert_response :success
  end

  test "a form from a different origin is still rejected" do
    get root_path, headers: PROXY_HEADERS
    token = css_select("meta[name=csrf-token]").first["content"]

    assert_no_difference -> { ListItem.count } do
      post list_items_path, params: { list_item: { name: "Banana" }, authenticity_token: token },
        headers: PROXY_HEADERS.merge("Origin" => "https://evil.example", "Accept" => "text/vnd.turbo-stream.html")
    end
  end
end
