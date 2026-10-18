require "test_helper"

class ListItemSnapshotTest < ActiveSupport::TestCase
  test "round-trips the attributes needed to restore an item" do
    item = list_items(:detergent)

    snapshot = ListItemSnapshot.load(ListItemSnapshot.dump(item))

    assert_equal item.id, snapshot["id"]
    assert_equal item.household_id, snapshot["household_id"]
    assert_equal "Detergente", snapshot["name"]
    assert_equal "purchased", snapshot["status"]
    assert_equal item.purchased_at.to_i, Time.iso8601(snapshot["purchased_at"]).to_i
    assert_equal item.created_at.round(6), Time.iso8601(snapshot["created_at"])
  end

  test "rejects a tampered token" do
    token = ListItemSnapshot.dump(list_items(:milk))
    tampered = token.sub(/.\z/) { |char| char == "A" ? "B" : "A" }

    assert_nil ListItemSnapshot.load(tampered)
  end

  test "rejects a token signed with another purpose or key" do
    forged = ActiveSupport::MessageVerifier.new("not the secret", serializer: JSON).generate({ "name" => "Leite" })

    assert_nil ListItemSnapshot.load(forged)
    assert_nil ListItemSnapshot.load(Rails.application.message_verifier(:other).generate({ "name" => "Leite" }))
  end

  test "rejects an expired token" do
    token = ListItemSnapshot.dump(list_items(:milk))

    travel ListItemSnapshot::EXPIRES_IN + 1.second do
      assert_nil ListItemSnapshot.load(token)
    end
    assert_not_nil ListItemSnapshot.load(token)
  end

  test "rejects blank and non-string tokens" do
    assert_nil ListItemSnapshot.load(nil)
    assert_nil ListItemSnapshot.load("")
    assert_nil ListItemSnapshot.load({ "a" => "b" })
    assert_nil ListItemSnapshot.load([ "a" ])
  end
end
