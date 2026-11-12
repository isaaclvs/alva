require "test_helper"

class SyncShoppingTest < ActionDispatch::IntegrationTest
  setup do
    @old_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("teste", cost: 4).to_s
    _, @token = AndroidDevice.issue(household: households(:home), name: "Native", scope: AndroidDevice::SYNC)
    @headers = { "Authorization" => "Bearer #{@token}" }
  end
  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @old_digest }

  test "finish over the endpoint archives what was observed, replays after a lost answer and matches the snapshot" do
    bought = list_items(:detergent)
    command = { id: SecureRandom.uuid, type: "finish_shopping",
      payload: { items: [ { public_id: bought.public_id, lock_version: bought.lock_version } ] } }

    original = nil
    2.times do |index|
      post "/api/v1/commands", params: { commands: [ command ] }, as: :json, headers: @headers
      assert_response :success
      result = response.parsed_body.fetch("results").first
      assert_equal(index.zero? ? "applied" : "duplicate", result["status"])
      original ||= result.fetch("data")
      assert_equal original, result["data"]
    end

    get "/api/v1/snapshot", headers: @headers
    snapshot = response.parsed_body
    trip = snapshot.fetch("shopping_trips").sole
    assert_equal original.dig("trip", "public_id"), trip["public_id"]
    assert_equal [ bought.public_id ], snapshot.fetch("archived_items").map { |entry| entry["public_id"] }
    assert_equal trip["public_id"], snapshot.fetch("archived_items").first["shopping_trip_public_id"]
    assert_equal [ list_items(:milk).public_id ], snapshot.fetch("list_items").map { |entry| entry["public_id"] }
    assert_equal 1, ShoppingTrip.count
  end

  test "a stale finish is refused with the items to review and asks for a snapshot" do
    bought = list_items(:detergent)
    command = { id: SecureRandom.uuid, type: "finish_shopping",
      payload: { items: [ { public_id: bought.public_id, lock_version: bought.lock_version + 5 } ] } }

    post "/api/v1/commands", params: { commands: [ command ] }, as: :json, headers: @headers

    body = response.parsed_body
    result = body.fetch("results").first
    assert_equal [ "conflict", "precondition_failed", "version_changed" ], [ result["status"], result["error"], result.dig("data", "reason") ]
    assert_equal [ bought.public_id ], result.dig("data", "problems").map { |problem| problem["public_id"] }
    assert body["snapshot_required"]
    assert_equal 0, ShoppingTrip.count
  end

  test "undo over the endpoint after a long delay restores the items and replays the receipt" do
    bought = list_items(:detergent)
    finish = { id: SecureRandom.uuid, type: "finish_shopping",
      payload: { items: [ { public_id: bought.public_id, lock_version: bought.lock_version } ] } }
    post "/api/v1/commands", params: { commands: [ finish ] }, as: :json, headers: @headers
    trip_id = response.parsed_body.dig("results", 0, "data", "trip", "public_id")
    undo = { id: SecureRandom.uuid, type: "undo_shopping", depends_on: finish[:id], payload: { trip_public_id: trip_id } }

    original = nil
    travel 30.days do
      2.times do |index|
        post "/api/v1/commands", params: { commands: [ undo ] }, as: :json, headers: @headers
        assert_response :success
        result = response.parsed_body.fetch("results").first
        assert_equal(index.zero? ? "applied" : "duplicate", result["status"])
        original ||= result.fetch("data")
        assert_equal original, result["data"]
        assert_equal finish[:id], result.dig("data", "finish_id")
      end
      get "/api/v1/snapshot", headers: @headers
      snapshot = response.parsed_body
      assert_empty snapshot.fetch("shopping_trips")
      assert_empty snapshot.fetch("archived_items")
      restored = snapshot.fetch("list_items").find { |entry| entry["public_id"] == bought.public_id }
      assert_equal [ "purchased", original.dig("items", 0, "lock_version") ], restored.values_at("status", "lock_version")
      assert snapshot.fetch("tombstones").any? { |entry| entry["public_id"] == trip_id && entry["record_type"] == "shopping_trip" }
    end
  end
end
