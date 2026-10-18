# "Comprar de novo": the things this household buys the most, according to the
# purchase history (archived items), minus whatever is on the list right now.
# Ranked by how many trips each name was bought in, then by how recently.
# Names are grouped after normalization, and the most recent spelling and
# category are the ones shown. Done in Ruby over plucked columns: the history
# is small and this runs on every list refresh.
class BuyAgainSuggestions
  Suggestion = Struct.new(:name, :category, :trips, :last_purchased_at)

  def self.call(household, limit: 8)
    new(household, limit).call
  end

  def initialize(household, limit)
    @household = household
    @limit = limit
  end

  def call
    on_list = @household.list_items.on_list.pluck(:name).map { |name| TextNormalizer.call(name) }.to_set

    history = @household.list_items.archived.pluck(:name, :category_id, :shopping_trip_id, :purchased_at, :updated_at, :id)
      .group_by { |name, *| TextNormalizer.call(name) }
      .reject { |key, _| key.blank? || on_list.include?(key) }
    return [] if history.empty?

    ranked = history.map { |_, rows| summarize(rows) }
      .sort_by { |trips, recency, name, *| [ -trips, -recency, name ] }
      .first(@limit)

    categories = Category.where(id: ranked.filter_map { |*, category_id| category_id }).index_by(&:id)
    ranked.map { |trips, recency, name, category_id| Suggestion.new(name, categories[category_id], trips, Time.zone.at(recency)) }
  end

  private

  # [trips, recency, latest name, latest category_id] for the rows of one name.
  def summarize(rows)
    latest = rows.max_by { |_, _, _, purchased_at, updated_at, id| [ (purchased_at || updated_at).to_f, id ] }
    name, category_id, _, purchased_at, updated_at = latest
    [ rows.map { |row| row[2] }.uniq.size, (purchased_at || updated_at).to_f, name, category_id ]
  end
end
