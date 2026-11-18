module Api
  module V1
    # Read-only shopping history for the native app: the trips "Compra feita"
    # archived, one month per page (see ShoppingHistory, the same service the
    # PWA's "Compras" screen reads). Additive to the v1 contract: an older
    # client never asks for it, and this endpoint writes nothing.
    #
    # The household comes from the bearer credential (BaseController), like the
    # snapshot; a month the house has no trip for falls back to the newest one,
    # as on the PWA screen.
    class HistoryController < BaseController
      def index
        page = ShoppingHistory.call(current_household, month: params[:month])
        render json: document(page)
      end

      private

      def document(page)
        {
          month: page.month&.key,
          months: page.months.map { |month| { key: month.key, trip_count: month.trip_count } },
          older_month: page.older_month&.key,
          newer_month: page.newer_month&.key,
          trips: page.trips.map { |entry| trip(entry) }
        }
      end

      # A trip with its items, already in the order the PWA shows them (category
      # position, then name without accents). Categories are referenced by
      # `public_id`, like every other record of the contract.
      def trip(entry)
        {
          public_id: entry.trip.public_id,
          finished_at: entry.trip.finished_at.utc.iso8601(6),
          items_count: entry.items_count,
          items: entry.groups.flat_map do |_category, items|
            items.map do |item|
              { public_id: item.public_id, name: item.name, category_public_id: item.category&.public_id }
            end
          end
        }
      end
    end
  end
end
