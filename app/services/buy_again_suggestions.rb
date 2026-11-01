# Rank archived names in SQLite, returning only the few suggestions displayed.
# Stored normalized keys share the same matching rules as additions/corrections.
class BuyAgainSuggestions
  Suggestion = Struct.new(:name, :category, :trips, :last_purchased_at)
  LIMIT = 5

  def self.call(household, limit: LIMIT)
    new(household, limit).call
  end

  def initialize(household, limit)
    @household = household
    @limit = Integer(limit)
    raise ArgumentError, "limit must be nonnegative" if @limit.negative?
  end

  def call
    return [] if @limit.zero?

    # Frequency counts trips (not rows); rn chooses the latest spelling and
    # category, breaking same-time ties by id, just like the original ranking.
    sql = <<~SQL
      WITH history AS (
        SELECT id, name, normalized_name, category_id, shopping_trip_id, purchased_at, updated_at
        FROM list_items WHERE household_id = :household_id AND shopping_trip_id IS NOT NULL
      ),
      ranked AS (
        SELECT history.*, ROW_NUMBER() OVER (
          PARTITION BY normalized_name ORDER BY COALESCE(purchased_at, updated_at) DESC, id DESC
        ) AS rn FROM history
      ),
      frequency AS (
        SELECT normalized_name, COUNT(DISTINCT shopping_trip_id) AS trips
        FROM history GROUP BY normalized_name
      )
      SELECT ranked.name, ranked.category_id, frequency.trips,
             COALESCE(ranked.purchased_at, ranked.updated_at) AS last_purchased_at
      FROM ranked JOIN frequency USING (normalized_name)
      WHERE rn = 1 AND normalized_name <> '' AND normalized_name NOT IN (
        SELECT normalized_name FROM list_items WHERE household_id = :household_id AND shopping_trip_id IS NULL
      )
      ORDER BY trips DESC, last_purchased_at DESC, name ASC
      LIMIT :limit
    SQL
    rows = ListItem.connection.select_all(ListItem.sanitize_sql_array([ sql, { household_id: @household.id, limit: @limit } ]))
    categories = Category.where(id: rows.filter_map { |row| row["category_id"] }).index_by(&:id)
    time_type = ListItem.type_for_attribute("purchased_at")
    rows.map do |row|
      Suggestion.new(row["name"], categories[row["category_id"]], row["trips"], time_type.deserialize(row["last_purchased_at"]))
    end
  end
end
