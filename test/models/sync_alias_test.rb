require "test_helper"

class SyncAliasTest < ActiveSupport::TestCase
  setup do
    @home = households(:home)
    @alias = SyncAlias.new(household: @home, public_id: SecureRandom.uuid, canonical_public_id: list_items(:milk).public_id,
      command_id: SecureRandom.uuid)
  end

  test "an alias points at a canonical record of its household" do
    assert @alias.save
  end

  test "it needs well-formed UUIDs and cannot point at itself" do
    @alias.public_id = "nao-uuid"
    assert_not @alias.valid?

    @alias.public_id = @alias.canonical_public_id
    assert_not @alias.valid?
    assert_includes @alias.errors.attribute_names, :canonical_public_id

    @alias.canonical_public_id = nil
    assert_not @alias.valid?
  end

  test "a UUID is aliased once, for every household: the database refuses a second claim" do
    @alias.save!
    other = Household.create!(name: "Praia")

    assert_raises(ActiveRecord::RecordNotUnique) do
      SyncAlias.new(household: other, public_id: @alias.public_id, canonical_public_id: SecureRandom.uuid,
        command_id: SecureRandom.uuid).save!(validate: false)
    end
  end
end
