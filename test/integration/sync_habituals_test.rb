require "test_helper"

class SyncHabitualsTest < ActionDispatch::IntegrationTest
  setup do
    @old_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("teste", cost: 4).to_s
    @home = households(:home)
    _, @token = AndroidDevice.issue(household: @home, name: "Native", scope: AndroidDevice::SYNC)
    @headers = { "Authorization" => "Bearer #{@token}" }
  end
  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @old_digest }

  test "create, add and remove over the endpoint, each visible in the snapshot" do
    created = command("create_habitual", name: "Café")
    result = send_command(created)
    assert_equal "applied", result["status"]
    habitual_id = result.dig("data", "item", "public_id")

    get "/api/v1/snapshot", headers: @headers
    assert_equal [ habitual_id ], response.parsed_body["habitual_items"].map { |habitual| habitual["public_id"] }

    added = send_command(command("add_habituals", public_ids: [ habitual_id ]))
    assert_equal "applied", added["status"]
    assert @home.pending_item_named("Café")

    removed = send_command(command("remove_habitual", public_id: habitual_id))
    assert_equal "applied", removed["status"]
    get "/api/v1/snapshot", headers: @headers
    snapshot = response.parsed_body
    assert_empty snapshot["habitual_items"]
    assert_includes snapshot["tombstones"], { "record_type" => "habitual_item", "public_id" => habitual_id, "revision" => removed["revision"] }
    assert snapshot["list_items"].any? { |item| item["name"] == "Café" }, "removing the habitual keeps the item"
  end

  test "a lost answer is resent with the same bytes and creates one habitual" do
    command = command("create_habitual", name: "Café")
    first = send_command(command)
    second = send_command(command)

    assert_equal %w[applied duplicate], [ first["status"], second["status"] ]
    assert_equal first["data"], second["data"]
    assert_equal 1, @home.habitual_items.count
  end

  test "an add_only credential cannot run the commands" do
    _, add_only = AndroidDevice.issue(household: @home, name: "Widget")

    post "/api/v1/commands", params: { commands: [ command("create_habitual", name: "Café") ] }, as: :json,
      headers: { "Authorization" => "Bearer #{add_only}" }

    assert_response :forbidden
    assert_equal 0, @home.habitual_items.count
  end

  test "the PWA habitual controller still works and sees what sync created" do
    send_command(command("create_habitual", name: "Café"))
    post login_path, params: { password: "teste" }

    post add_all_habitual_items_path, as: :turbo_stream

    assert_response :success
    assert @home.pending_item_named("Café")
  end

  private

  def command(type, **payload) = { id: SecureRandom.uuid, type:, payload: }

  def send_command(command)
    post "/api/v1/commands", params: { commands: [ command ] }, as: :json, headers: @headers
    assert_response :success
    response.parsed_body.fetch("results").first
  end
end
