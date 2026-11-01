require "test_helper"

class HouseholdAccessTest < ActionDispatch::IntegrationTest
  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-de-teste", cost: 4).to_s
    SessionsController::LOGIN_RATE_LIMIT_STORE.clear
    https!
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  test "anonymous requests cannot read the list, export or mutate it" do
    [ root_path, list_export_path, edit_list_item_path(list_items(:milk)) ].each do |path|
      get path
      assert_redirected_to login_path
      assert_equal "no-store", response.headers["Cache-Control"]
    end
    assert_no_difference -> { ListItem.count } do
      post list_items_path, params: { list_item: { name: "Intruso" } }
    end
    assert_redirected_to login_path
    patch toggle_list_item_path(list_items(:milk))
    assert_redirected_to login_path
    assert list_items(:milk).reload.pending?
    get root_path, headers: { "Accept" => "text/vnd.turbo-stream.html" }
    assert_redirected_to login_path
  end

  test "password grants persistent secure access and logout removes it" do
    post login_path, params: { password: "errada" }
    assert_response :unprocessable_entity
    assert_includes response.body, "Senha incorreta"
    post login_path, params: { password: "senha-de-teste" }
    assert_redirected_to root_path
    cookie = response.headers["Set-Cookie"].to_s.downcase
    assert_includes cookie, "httponly"
    assert_includes cookie, "secure"
    assert_includes cookie, "expires="
    get root_path
    assert_response :success
    get list_export_path
    assert_response :success
    delete logout_path
    get root_path
    assert_redirected_to login_path
  end

  test "rotating the password invalidates an existing device" do
    post login_path, params: { password: "senha-de-teste" }
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("outra-senha", cost: 4).to_s
    get root_path
    assert_redirected_to login_path
  end

  test "a forged cookie does not grant access" do
    cookies[HouseholdAccess::COOKIE] = HouseholdAccess.token
    get root_path
    assert_redirected_to login_path
  end

  test "login attempts are limited" do
    10.times { post login_path, params: { password: "errada" } }
    assert_response :unprocessable_entity
    post login_path, params: { password: "senha-de-teste" }
    assert_response :too_many_requests
    travel 4.minutes do
      post login_path, params: { password: "senha-de-teste" }
      assert_redirected_to root_path
    end
  end

  test "the login form retains CSRF protection on HTTPS" do
    previous = ActionController::Base.allow_forgery_protection
    ActionController::Base.allow_forgery_protection = true
    get login_path
    token = css_select("meta[name=csrf-token]").first["content"]
    post login_path, params: { password: "senha-de-teste", authenticity_token: token },
      headers: { "Origin" => "https://evil.example" }
    assert_response :unprocessable_entity
    get root_path
    assert_redirected_to login_path
    post login_path, params: { password: "senha-de-teste", authenticity_token: token },
      headers: { "Origin" => "https://www.example.com" }
    assert_redirected_to root_path
  ensure
    ActionController::Base.allow_forgery_protection = previous
  end

  test "PWA resources and health are available without the household password" do
    get pwa_manifest_path(format: :json)
    assert_response :success
    get pwa_service_worker_path(format: :js)
    assert_response :success
    get rails_health_check_path
    assert_response :success
  end
end
