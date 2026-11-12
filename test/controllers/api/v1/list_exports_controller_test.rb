require "test_helper"

class Api::V1::ListExportsControllerTest < ActionDispatch::IntegrationTest
  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @sync_device, @sync_token = AndroidDevice.issue(household: @home, name: "Sync", scope: AndroidDevice::SYNC)
  end

  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest }

  test "a sync credential downloads the PDF of its household's pending items" do
    travel_to Time.zone.local(2026, 3, 7, 12) do
      get "/api/v1/list.pdf", headers: bearer(@sync_token)

      assert_response :success
      assert_equal "application/pdf", response.media_type
      assert_match(/\Aattachment;.*filename="lista-2026-03-07\.pdf"/, response.headers["Content-Disposition"])
      assert_equal "no-store", response.headers["Cache-Control"]
    end
    assert response.body.start_with?("%PDF")
    text = pdf_text(response.body)
    assert_includes text, "Leite"
    assert_not_includes text, "Detergente"
  end

  test "it is the PDF the PWA serves, with the same name and content" do
    travel_to Time.zone.local(2026, 3, 7, 12) do
      get "/api/v1/list.pdf", headers: bearer(@sync_token)
      native = response.body
      post login_path, params: { password: "senha-da-casa" }
      get list_export_path
      assert_response :success

      assert_equal pdf_text(response.body), pdf_text(native)
      assert_equal response.headers["Content-Disposition"], "attachment; filename=\"lista-2026-03-07.pdf\"; filename*=UTF-8''lista-2026-03-07.pdf"
    end
  end

  test "never includes another household's items" do
    beach = Household.create!(name: "Praia")
    beach.list_items.create!(name: "Coco da praia")

    get "/api/v1/list.pdf", headers: bearer(@sync_token)

    assert_not_includes pdf_text(response.body), "Coco"
  end

  test "missing, malformed, unknown and revoked credentials are unauthorized like the other endpoints" do
    [ {}, { "Authorization" => "Bearer " }, { "Authorization" => "bearer #{@sync_token}" },
      { "Authorization" => "Bearer nao-existe" } ].each do |headers|
      get "/api/v1/list.pdf", headers: headers
      assert_response :unauthorized, headers.inspect
      assert_equal({ "error" => "unauthorized" }, response.parsed_body)
      assert_equal "no-store", response.headers["Cache-Control"]
    end

    @sync_device.revoke!
    get "/api/v1/list.pdf", headers: bearer(@sync_token)
    assert_response :unauthorized
    assert_not_includes response.body, "%PDF"
  end

  test "the widget's add_only credential is forbidden" do
    _, add_only = AndroidDevice.issue(household: @home, name: "Widget")

    get "/api/v1/list.pdf", headers: bearer(add_only)

    assert_response :forbidden
    assert_equal({ "error" => "forbidden_scope" }, response.parsed_body)
  end

  test "a PWA cookie does not authorize it" do
    post login_path, params: { password: "senha-da-casa" }

    get "/api/v1/list.pdf"

    assert_response :unauthorized
  end

  test "only GET without a format suffix exists" do
    post "/api/v1/list.pdf", headers: bearer(@sync_token)
    assert_response :not_found
    get "/api/v1/list.pdf.json", headers: bearer(@sync_token)
    assert_response :not_found
  end

  test "the PWA route is unchanged and still needs the cookie when a password is set" do
    get list_export_path
    assert_response :redirect
    assert_equal "/lista.pdf", list_export_path
  end

  private

  def bearer(token) = { "Authorization" => "Bearer #{token}" }

  def pdf_text(bytes) = PDF::Reader.new(StringIO.new(bytes)).pages.map(&:text).join("\n")
end
