require "test_helper"

# Every record the sync protocol exposes carries a stable UUID next to its
# integer key: the Android client generates it offline and sends it back (see
# docs/plans/android-sync-contract-v1.md).
class PublicIdTest < ActiveSupport::TestCase
  UUID = /\A\h{8}-\h{4}-\h{4}-\h{4}-\h{12}\z/

  test "assigns a UUID when a synchronized record is created" do
    item = households(:home).list_items.create!(name: "Banana")

    assert_match UUID, item.public_id
  end

  test "assigns the UUID before validation, so a new record is valid" do
    household = Household.new(name: "Identidade")

    assert household.valid?
    assert_match UUID, household.public_id
  end

  test "gives every record its own UUID, across tables" do
    household = Household.create!(name: "Identidade")
    records = [
      household,
      household.list_items.create!(name: "Arroz"),
      household.habitual_items.create!(name: "Feijão"),
      household.shopping_trips.create!(finished_at: Time.current),
      CatalogItem.create!(name: "Novo item", category: categories(:other))
    ]

    assert_equal records.size, records.map(&:public_id).uniq.size
  end

  test "keeps a public_id a client already generated" do
    given = "5f0c2f0e-0000-4000-8000-000000000000"
    item = households(:home).list_items.create!(name: "Banana", public_id: given)

    assert_equal given, item.public_id
  end

  test "the database refuses a repeated public_id" do
    household = households(:home)
    household.list_items.create!(name: "Banana", public_id: "60000000-0000-4000-8000-000000000000")

    assert_raises(ActiveRecord::RecordNotUnique) do
      ListItem.insert!({ name: "Outra", normalized_name: "outra", status: 0, household_id: household.id,
        public_id: list_items(:milk).public_id, created_at: Time.current, updated_at: Time.current })
    end
  end

  test "fixtures carry a public_id for every synchronized table" do
    records = [ households(:home), categories(:dairy), catalog_items(:milk), list_items(:milk) ]

    records.each { |record| assert_match UUID, record.public_id.to_s, "#{record.class} fixture" }
  end
end
