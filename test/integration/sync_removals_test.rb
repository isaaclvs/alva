require "test_helper"

class SyncRemovalsTest < ActionDispatch::IntegrationTest
  setup do
    @old_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("teste", cost: 4).to_s
    _, @token = AndroidDevice.issue(household: households(:home), name: "Native", scope: AndroidDevice::SYNC)
  end
  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @old_digest }

  test "remove endpoint keeps public references and replays the original receipt after a lost answer" do
    item = list_items(:milk)
    command = { id: SecureRandom.uuid, type: "remove_item", payload: { public_id: item.public_id },
      preconditions: { lock_version: item.lock_version } }
    original = nil
    2.times do |index|
      post "/api/v1/commands", params: { commands: [ command ] }, as: :json,
        headers: { "Authorization" => "Bearer #{@token}" }
      assert_response :success
      result = response.parsed_body.fetch("results").first
      assert_equal(index.zero? ? "applied" : "duplicate", result["status"])
      original ||= result.fetch("data")
      assert_equal original, result["data"]
      assert_equal categories(:dairy).public_id, result.dig("data", "item", "category_public_id")
      assert_not result.fetch("data").fetch("item").key?("category_id")
    end
    assert_not ListItem.exists?(public_id: item.public_id)
    assert_equal 1, SyncCommand.where(command_id: command[:id]).count
    assert_equal 1, SyncTombstone.where(public_id: item.public_id).count
  end
end
