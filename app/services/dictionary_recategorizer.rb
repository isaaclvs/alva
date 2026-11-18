# Moves live items out of the "Outros" fallback when the dictionary now knows
# where they belong. Existing installs added "Presunto" or "Ração" before the
# catalog had those names, so they sit in Outros until someone fixes them by
# hand; running this once after the seeds carry the new entries puts them in
# the right place without touching anything the household decided.
#
# Safe by construction: only items currently in the fallback are candidates,
# and a category picked by hand already taught the dictionary (ItemCategorizer
# learns it), so `ItemCategorizer.call` returns that category and the item is
# left alone. Items with no category ("Sem categoria") and archived items
# (history keeps its own category) are never touched.
class DictionaryRecategorizer
  # Returns how many items moved in this household. `sync: false` is for a
  # caller that publishes one revision for everything it does (db:seed, which
  # ends with DictionaryChange.record!).
  def self.call(household, sync: true)
    new(household, sync:).call
  end

  # Every household; returns how many items moved in total.
  def self.call_all(sync: true)
    Household.order(:id).sum { |household| call(household, sync:) }
  end

  def initialize(household, sync: true)
    @household = household
    @sync = sync
  end

  def call
    moved = 0
    @household.batch_list_broadcasts do
      @household.with_list_lock do
        candidates.each do |item|
          category = ItemCategorizer.call(item.name)
          next if category.nil? || category.name == ItemCategorizer::FALLBACK_CATEGORY

          item.update!(category: category)
          moved += 1
        end
        @household.record_shared_change! if @sync && moved.positive?
      end
    end
    moved
  end

  private

  def candidates
    @household.list_items.on_list.includes(:category)
      .select { |item| item.category&.name == ItemCategorizer::FALLBACK_CATEGORY }
  end
end
