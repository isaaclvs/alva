require "test_helper"

class SyncItemStateUndoTest < ActionDispatch::IntegrationTest
  setup do
    @old_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("teste", cost: 4).to_s
    _, @token = AndroidDevice.issue(household: households(:home), name: "Native", scope: AndroidDevice::SYNC)
    @headers = { "Authorization" => "Bearer #{@token}" }
  end
  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @old_digest }

  test "undo of a mark over the endpoint restores the item, replays after a lost answer and matches the snapshot" do
    item = list_items(:milk)
    mark = { id: SecureRandom.uuid, type: "set_item_state", payload: { public_id: item.public_id, desired: "purchased" },
      preconditions: { lock_version: item.lock_version } }
    undo = { id: SecureRandom.uuid, type: "undo_item_state", depends_on: mark[:id],
      payload: { public_id: item.public_id, status: "pending", purchased_at: nil } }

    post "/api/v1/commands", params: { commands: [ mark ] }, as: :json, headers: @headers
    assert_equal "applied", response.parsed_body.fetch("results").first["status"]

    original = nil
    2.times do |index|
      post "/api/v1/commands", params: { commands: [ undo ] }, as: :json, headers: @headers
      assert_response :success
      result = response.parsed_body.fetch("results").first
      assert_equal(index.zero? ? "applied" : "duplicate", result["status"])
      original ||= result.fetch("data")
      assert_equal original, result["data"]
      assert_equal [ "pending", nil, 2 ], result.dig("data", "item").values_at("status", "purchased_at", "lock_version")
    end

    get "/api/v1/snapshot", headers: @headers
    restored = response.parsed_body.fetch("list_items").find { |entry| entry["public_id"] == item.public_id }
    assert_equal [ "pending", nil, 2 ], restored.values_at("status", "purchased_at", "lock_version")
    assert_equal 1, SyncCommand.where(command_id: undo[:id]).count
  end

  test "a stale undo answers conflict with the current state and snapshot_required" do
    item = list_items(:milk)
    undo = { id: SecureRandom.uuid, type: "undo_item_state",
      payload: { public_id: item.public_id, status: "purchased", purchased_at: 1.hour.ago.utc.iso8601(6) },
      preconditions: { lock_version: 7 } }

    post "/api/v1/commands", params: { commands: [ undo ] }, as: :json, headers: @headers

    assert_response :success
    body = response.parsed_body
    result = body.fetch("results").first
    assert_equal [ "conflict", "precondition_failed", "version_changed", 7 ],
      [ result["status"], result["error"], result.dig("data", "reason"), result.dig("data", "expected_lock_version") ]
    assert_equal "pending", result.dig("data", "current", "status")
    assert body["snapshot_required"]
    assert item.reload.pending?
  end
end
