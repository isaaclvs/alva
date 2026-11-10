require "test_helper"

# Runs the examples in test/fixtures/files/matching_corpus.json against the
# Rails implementation. The Android client runs the same file against its Kotlin
# port (MatchingCorpusTest), so a change in behavior on either side fails one of
# the two suites. See docs/plans/android-local-persistence.md.
class MatchingCorpusTest < ActiveSupport::TestCase
  CORPUS = JSON.parse(Rails.root.join("test/fixtures/files/matching_corpus.json").read)

  test "the corpus is the version both runtimes understand" do
    assert_equal 1, CORPUS["version"]
    %w[transliteration normalize dictionary categorize split add].each do |section|
      assert CORPUS[section].present?, "missing #{section}"
    end
  end

  test "the transliteration table is exactly the one I18n applies" do
    # Kotlin keeps its own copy of this table; if an i18n upgrade changes it,
    # this fails so the corpus and the port are updated together.
    assert_equal I18n::Backend::Transliterator::HashTransliterator::DEFAULT_APPROXIMATIONS, CORPUS["transliteration"]
    CORPUS["transliteration"].each do |char, ascii|
      assert_equal ascii, I18n.transliterate(char), "transliterate(#{char.inspect})"
    end
  end

  test "TextNormalizer produces the plain form and the key of every example" do
    CORPUS["normalize"].each do |example|
      assert_equal example["plain"], TextNormalizer.plain(example["input"]), "plain(#{example['input'].inspect})"
      assert_equal example["key"], TextNormalizer.call(example["input"]), "call(#{example['input'].inspect})"
    end
  end

  test "ItemCategorizer picks the category of every example from the shared dictionary" do
    load_dictionary

    CORPUS["categorize"].each do |example|
      assert_equal example["category"], ItemCategorizer.call(example["input"])&.name, "categorize(#{example['input'].inspect})"
    end
  end

  test "ItemNameSplitter splits every example the same way" do
    CORPUS["split"].each do |example|
      assert_equal example["names"], ItemNameSplitter.call(example["input"]), "split(#{example['input'].inspect})"
    end
  end

  test "Household adds every example with the same outcomes and the same final list" do
    load_dictionary

    CORPUS["add"].each do |example|
      household = Household.create!(name: "Corpus: #{example['title']}")
      seed_list(household, example["existing"])

      results =
        if example["names"]
          household.add_item_names(example["names"])
        else
          household.add_items(example["input"])
        end

      assert_equal example["outcomes"], results.map { |result| result.outcome.to_s }, example["title"]
      assert_equal example["final"], live_list(household), example["title"]
    end
  end

  private

  def load_dictionary
    CatalogItem.delete_all
    CORPUS["dictionary"]["categories"].each do |attrs|
      category = Category.find_or_initialize_by(name: attrs["name"])
      category.position = attrs["position"]
      category.save!
    end
    CORPUS["dictionary"]["entries"].each do |attrs|
      CatalogItem.create!(name: attrs["name"], category: Category.find_by!(name: attrs["category"]), learned: attrs["learned"])
    end
  end

  # Purchased items get increasing purchased_at in the order given, so "the most
  # recently purchased" is the last one listed.
  def seed_list(household, existing)
    trip = nil
    base = Time.utc(2026, 1, 1)
    household.batch_list_broadcasts do
      existing.each_with_index do |attrs, index|
        category = attrs["category"] && Category.find_by!(name: attrs["category"])
        trip ||= household.shopping_trips.create!(finished_at: base) if attrs["archived"]
        item = household.list_items.create!(
          name: attrs["name"], category: category, status: attrs["status"],
          purchased_at: (base + index.minutes if attrs["status"] == "purchased"),
          shopping_trip: (trip if attrs["archived"])
        )
        item.update_columns(category_id: nil) if category.nil?
      end
    end
  end

  def live_list(household)
    household.list_items.on_list.includes(:category).order(:id).map do |item|
      { "name" => item.name, "status" => item.status, "category" => item.category&.name }
    end
  end
end
