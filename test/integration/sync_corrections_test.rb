require "test_helper"
require "support/sync_command_builders"

class SyncCorrectionsTest < ActionDispatch::IntegrationTest
  include SyncCommandBuilders

  setup do
    @old_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("teste", cost: 4).to_s
    @home = households(:home)
    @device, @token = AndroidDevice.issue(household: @home, name: "Native", scope: AndroidDevice::SYNC)
  end
  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @old_digest }

  test "real command endpoint returns correction and replay after an unobserved response" do
    item = list_items(:milk)
    command = correct_item_command(item.public_id, "Leite corrigido", lock_version: 0,
      category_id: categories(:other).public_id, dictionary_revision: 0)
    2.times do |index|
      post "/api/v1/commands", params: { commands: [ command ] }, as: :json,
        headers: { "Authorization" => "Bearer #{@token}" }
      assert_response :success
      result = response.parsed_body.fetch("results").first
      assert_equal(index.zero? ? "applied" : "duplicate", result["status"])
      assert_equal categories(:other).public_id, result.dig("data", "item", "category_public_id")
    end
    assert_equal 1, SyncCommand.where(command_id: command["id"]).count
    get "/api/v1/snapshot", headers: { "Authorization" => "Bearer #{@token}" }
    assert_response :success
    assert_includes response.body, "Leite corrigido"
    assert CatalogItem.find_by!(normalized_name: TextNormalizer.call("Leite corrigido")).learned?
  end

  test "stale learning is consumed while preserving category and reports review to the client" do
    DictionaryChange.bump_revision!
    item = list_items(:milk)
    command = correct_item_command(item.public_id, "Leite corrigido", lock_version: 0,
      category_id: categories(:other).public_id, dictionary_revision: 0)
    post "/api/v1/commands", params: { commands: [ command ] }, as: :json,
      headers: { "Authorization" => "Bearer #{@token}" }
    assert_response :success
    result = response.parsed_body.fetch("results").first
    assert_equal "applied", result["status"]
    assert_equal categories(:other).public_id, result.dig("data", "learning_pending", "category_id")
    assert_equal categories(:other).id, item.reload.category_id
    assert_equal 1, SyncScope.revision_for(SyncScope::DICTIONARY)
  end
end
