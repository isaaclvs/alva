# Rank archived names in SQLite, returning only the few suggestions displayed.
# Stored normalized keys share the same matching rules as additions/corrections.
#
# A name with enough history also carries its cadence (#163): the median number
# of days between the days it was bought, and whether today is already past that
# mark since the last purchase. A due name comes first, so the one the house is
# probably missing is suggested before the merely frequent one, and its label
# says how often it tends to run out. No new table and no AI: the archive and
# `shopping_trips.finished_at` are enough.
#
# Everything is counted on the house's clock (the app time zone): a trip
# finished late at night belongs to that evening, also when UTC is already on
# the next day, and a name bought twice on the same day is one purchase day.
class BuyAgainSuggestions
  Suggestion = Struct.new(:name, :category, :trips, :last_purchased_at, :cadence_days, :due) do
    def due? = due == true
  end

  LIMIT = 5

  # Purchase days needed before a cadence is trusted: at least two intervals,
  # so one unusual gap cannot become the whole answer.
  MIN_PURCHASES = 3

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

    categories = Category.where(id: rows.filter_map { |row| row["category_id"] }).index_by(&:id)
    time_type = ListItem.type_for_attribute("purchased_at")
    purchase_days = purchase_days_by_name
    suggestions = rows.map do |row|
      days = purchase_days[row["normalized_name"]]
      cadence = cadence_days(days)
      Suggestion.new(row["name"], categories[row["category_id"]], row["trips"],
        time_type.deserialize(row["last_purchased_at"]), cadence, due?(days, cadence))
    end

    # Due first, each group keeping the frequency and recency ranking the SQL
    # returned: a hidden "Comprar de novo" is the expensive mistake.
    due, remaining = suggestions.partition(&:due?)
    (due + remaining).first(@limit)
  end

  private

  # Frequency counts trips (not rows); rn chooses the latest spelling and
  # category, breaking same-time ties by id, just like the original ranking.
  # No SQL LIMIT: a due name can rank low by frequency and must not be cut off
  # before the reordering above.
  def rows
    @rows ||= begin
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
        SELECT ranked.name, ranked.category_id, ranked.normalized_name, frequency.trips,
               COALESCE(ranked.purchased_at, ranked.updated_at) AS last_purchased_at
        FROM ranked JOIN frequency USING (normalized_name)
        WHERE rn = 1 AND normalized_name <> '' AND normalized_name NOT IN (
          SELECT normalized_name FROM list_items WHERE household_id = :household_id AND shopping_trip_id IS NULL
        )
        ORDER BY trips DESC, last_purchased_at DESC, name ASC
      SQL
      ListItem.connection.select_all(ListItem.sanitize_sql_array([ sql, { household_id: @household.id } ]))
    end
  end

  # One entry per name and trip: the days (on the house clock) it was bought on,
  # ascending and without repeats. A name that appears twice in the same trip is
  # one purchase; two trips finished on the same day are one purchase day too.
  def purchase_days_by_name
    sql = <<~SQL
      SELECT history.normalized_name, trips.finished_at
      FROM list_items history
      JOIN shopping_trips trips ON trips.id = history.shopping_trip_id
      WHERE history.household_id = :household_id AND history.shopping_trip_id IS NOT NULL
      GROUP BY history.normalized_name, history.shopping_trip_id
    SQL
    time_type = ShoppingTrip.type_for_attribute("finished_at")
    ListItem.connection.select_all(ListItem.sanitize_sql_array([ sql, { household_id: @household.id } ]))
      .group_by { |row| row["normalized_name"] }
      .transform_values do |purchases|
        purchases.map { |row| time_type.deserialize(row["finished_at"]).in_time_zone.to_date }.uniq.sort
      end
  end

  # Median days between consecutive purchase days, rounded to whole days: "a
  # cada ~12 dias" is what the label says. nil until there is history enough.
  def cadence_days(days)
    return nil if days.nil? || days.size < MIN_PURCHASES

    gaps = days.each_cons(2).map { |before, after| (after - before).to_i }.sort
    middle = gaps.size / 2
    median = gaps.size.odd? ? gaps[middle] : (gaps[middle - 1] + gaps[middle]) / 2.0
    median.round
  end

  # The cadence already passed: today is later than the last purchase plus the
  # median interval. Exactly on the mark is not yet late.
  def due?(days, cadence)
    cadence && (Date.current - days.last).to_i > cadence
  end
end
