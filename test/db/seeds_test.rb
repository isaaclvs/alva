require "test_helper"

# db:seed runs on every deploy (alva-update), so it must be safe to repeat and
# must keep what the household taught the dictionary.
class SeedsTest < ActiveSupport::TestCase
  test "running the seeds twice creates everything once" do
    Rails.application.load_seed
    counts = [ Household.count, Category.count, CatalogItem.count ]

    Rails.application.load_seed

    assert_equal counts, [ Household.count, Category.count, CatalogItem.count ]
    assert CatalogItem.exists?(normalized_name: TextNormalizer.call("Pão"))
  end

  test "running the seeds twice keeps every public_id" do
    Rails.application.load_seed
    public_ids = [ Household, Category, CatalogItem ].flat_map { |model| model.order(:id).pluck(:public_id) }

    Rails.application.load_seed

    assert_equal public_ids, [ Household, Category, CatalogItem ].flat_map { |model| model.order(:id).pluck(:public_id) }
  end

  test "running the seeds again with no catalog change moves no revision" do
    household = Household.find_by!(name: "Casa")
    before = [ household.sync_revision, SyncScope.revision_for(SyncScope::DICTIONARY) ]

    Rails.application.load_seed

    assert_equal before, [ household.reload.sync_revision, SyncScope.revision_for(SyncScope::DICTIONARY) ]
  end

  test "a seed run that changes the catalog moves the dictionary and the household" do
    household = Household.find_by!(name: "Casa")
    CatalogItem.find_by!(normalized_name: TextNormalizer.call("Leite"))
      .update!(category: Category.find_by!(name: "Higiene"))

    assert_difference -> { SyncScope.revision_for(SyncScope::DICTIONARY) }, 1 do
      assert_difference -> { household.reload.sync_revision }, 1 do
        Rails.application.load_seed
      end
    end
  end

  test "keeps a learned category instead of the catalog's" do
    Rails.application.load_seed
    hygiene = Category.find_by!(name: "Higiene")
    leite = CatalogItem.find_by!(normalized_name: TextNormalizer.call("Leite"))
    leite.update!(category: hygiene, learned: true)

    Rails.application.load_seed

    assert_equal hygiene, leite.reload.category
  end

  test "keeps a category learned from a correction, under any spelling, for the next item" do
    Rails.application.load_seed
    household = Household.find_by!(name: "Casa")
    pantry = Category.find_by!(name: "Mercearia")
    produce = Category.find_by!(name: "Hortifruti")

    tomato = household.add_item("Tomates").item
    assert tomato.correct(name: tomato.name, category_id: pantry.id)
    cherry = household.add_item("Tomate cereja").item
    assert cherry.correct(name: cherry.name, category_id: produce.id)
    [ tomato, cherry ].each(&:destroy!)

    assert_no_difference "CatalogItem.count" do
      Rails.application.load_seed
    end

    assert_equal pantry, household.add_item("tomate").item.category
    assert_equal produce, household.add_item("TOMATES CEREJA").item.category
  end

  test "updates entries that were not learned" do
    Rails.application.load_seed
    leite = CatalogItem.find_by!(normalized_name: TextNormalizer.call("Leite"))
    leite.update!(category: Category.find_by!(name: "Higiene"))

    Rails.application.load_seed

    assert_equal "Laticínios", leite.reload.category.name
  end

  test "moves items that were stuck in Outros once the catalog knows their name" do
    Rails.application.load_seed
    household = Household.find_by!(name: "Casa")
    others = Category.find_by!(name: "Outros")
    # An older catalog: neither name was there, so both landed in Outros.
    CatalogItem.where(normalized_name: [ "presunto", "racao" ]).delete_all
    ham = household.list_items.create!(name: "Presunto", category: others)
    food = household.list_items.create!(name: "Ração", category: others)
    # "Ração" in Outros was the household's own decision, so it stays.
    ItemCategorizer.learn("Ração", others)

    Rails.application.load_seed

    assert_equal "Frios e congelados", ham.reload.category.name
    assert_equal others, food.reload.category
  end

  test "seeding again leaves a list that is already right untouched" do
    Rails.application.load_seed
    household = Household.find_by!(name: "Casa")
    item = household.list_items.create!(name: "Ração", category: Category.find_by!(name: "Outros"))
    ItemCategorizer.learn("Ração", Category.find_by!(name: "Outros"))

    assert_no_difference -> { household.reload.sync_revision } do
      Rails.application.load_seed
    end
    assert_equal "Outros", item.reload.category.name
  end
end
