# Read-only view of what "Compra feita" archived: the finished trips, newest
# first, one month (the page) at a time, each with its items grouped exactly
# like the live list groups them. History is never edited here; the only action
# built on top of it is adding the names to the list again ("Adicionar tudo de
# novo"), which goes through Household#add_item_names like any other add.
#
# The month of a trip is the month of `finished_at` on the household's clock
# (America/Sao_Paulo), not in UTC: a shopping finished late at night belongs to
# that evening, also when it is already the next day in UTC.
class ShoppingHistory
  MONTH_KEY = /\A(\d{4})-(\d{2})\z/

  # One month of history, with how many trips it holds. `key` is the
  # "YYYY-MM" the screens and the API use to ask for it.
  Month = Data.define(:key, :year, :month, :trip_count) do
    def date = Date.new(year, month, 1)

    # The half-open range of finished_at values of this month, on the app clock.
    def range
      starts_at = date.in_time_zone
      starts_at...starts_at.next_month
    end
  end

  # One finished trip: [[category, items], ...] ordered by category position
  # (uncategorized last), the items by name without accents or case.
  Trip = Data.define(:trip, :groups, :items_count) do
    # The names to put on the list again, in the order the PWA shows them.
    def names = groups.flat_map { |_category, items| items.map(&:name) }
  end

  # One page of history: the month it shows (nil when there is no history) and
  # the trips of that month, newest first. `months` is every month with a trip,
  # newest first, so the screen can move one page at a time.
  Page = Data.define(:month, :months, :trips) do
    def empty? = trips.empty?

    # The page before (an older month) and after (a newer one), if any.
    def older_month
      index = month && months.index { |candidate| candidate.key == month.key }
      months[index + 1] if index
    end

    def newer_month
      index = month && months.index { |candidate| candidate.key == month.key }
      months[index - 1] if index&.positive?
    end
  end

  def self.call(household, month: nil)
    new(household).call(month: month)
  end

  # One trip of this household as the screen shows it (its names, its groups),
  # or nil when the id is not one of its finished trips. What "Adicionar tudo
  # de novo" re-adds comes from here, so it is the same list the person saw.
  def self.trip(household, id)
    new(household).trip(id)
  end

  def initialize(household)
    @household = household
  end

  # The page of `month` ("YYYY-MM"); without it, or with one that has no
  # trips, the newest month with history. An empty history is a page with no
  # month and no trips.
  def call(month: nil)
    months = months_of_history
    selected = months.find { |candidate| candidate.key == month } || months.first
    Page.new(month: selected, months:, trips: selected ? trips_in(selected) : [])
  end

  def trip(id)
    found = @household.shopping_trips.includes(list_items: :category).find_by(id: id)
    entry_for(found) if found
  end

  private

  # Months with at least one trip, newest first. Grouped here, on the app
  # clock, instead of in SQL: strftime would group by UTC.
  def months_of_history
    @months_of_history ||= @household.shopping_trips.order(finished_at: :desc, id: :desc)
      .pluck(:finished_at)
      .group_by { |finished_at| finished_at.in_time_zone.strftime("%Y-%m") }
      .map do |key, finished_ats|
        match = MONTH_KEY.match(key)
        Month.new(key:, year: match[1].to_i, month: match[2].to_i, trip_count: finished_ats.size)
      end
      .sort_by { |entry| [ -entry.year, -entry.month ] }
  end

  def trips_in(month)
    @household.shopping_trips.where(finished_at: month.range)
      .order(finished_at: :desc, id: :desc)
      .includes(list_items: :category)
      .map { |trip| entry_for(trip) }
  end

  def entry_for(trip)
    Trip.new(trip:, groups: groups_for(trip.list_items), items_count: trip.list_items.size)
  end

  # The same order as Household#list_items_by_category, without the pending /
  # purchased split: everything in a trip is already purchased.
  def groups_for(items)
    items.sort_by { |item| [ TextNormalizer.plain(item.name), item.id ] }
      .group_by(&:category)
      .sort_by { |category, _| category&.position || Float::INFINITY }
  end
end
