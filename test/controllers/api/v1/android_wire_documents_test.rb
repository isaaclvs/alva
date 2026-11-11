require "test_helper"

# The documents the Android client freezes (test/fixtures/files/android_wire_documents.json,
# the same file its Kotlin suites build from stored intents) as the real endpoint
# receives them. This proves Rails accepts those exact shapes; it is not the Android
# client talking to the server, which has no transport yet.
class Api::V1::AndroidWireDocumentsTest < ActionDispatch::IntegrationTest
  CASES = JSON.parse(Rails.root.join("test/fixtures/files/android_wire_documents.json").read).fetch("cases")

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @device, @token = AndroidDevice.issue(household: @home, name: "Celular da Ana", scope: AndroidDevice::SYNC)
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  CASES.each do |scenario|
    test "Rails accepts the frozen document of: #{scenario['name']}" do
      post "/api/v1/commands", params: { commands: scenario["documents"] },
        headers: { "Authorization" => "Bearer #{@token}" }, as: :json

      assert_response :ok
      results = JSON.parse(response.body)["results"]
      assert_equal scenario["documents"].map { |document| document["id"] }, results.map { |result| result["id"] }
      assert_equal scenario.dig("expect", "statuses"), results.map { |result| result["status"] }, results.to_json

      expected = scenario.dig("expect", "last_item")
      reported = Array(results.last.dig("data", "items")).last || results.last.dig("data", "item")
      expected.each { |key, value| assert_equal value, reported[key], "#{key} in #{reported.to_json}" }
    end
  end

  test "a revive whose entry reuses the purchase's own UUID does not revive (why the claim is new)" do
    detergent = list_items(:detergent)
    document = {
      "id" => SecureRandom.uuid, "type" => "add_items", "depends_on" => nil, "preconditions" => {},
      "payload" => { "items" => [ { "public_id" => detergent.public_id, "name" => "Detergente",
                                    "revives" => { "public_id" => detergent.public_id, "lock_version" => detergent.lock_version } } ] }
    }

    post "/api/v1/commands", params: { commands: [ document ] }, headers: { "Authorization" => "Bearer #{@token}" }, as: :json

    result = JSON.parse(response.body)["results"].first
    assert_equal [ "noop", [ "duplicate" ] ], [ result["status"], result.dig("data", "items").map { |entry| entry["outcome"] } ]
    assert detergent.reload.purchased?, "reusing the purchase's UUID must not bring it back: #{result.to_json}"
  end
end
