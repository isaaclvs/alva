class Household < ApplicationRecord
  has_many :list_items
  has_many :shopping_trips

  validates :name, presence: true

  # Pending item whose name matches after normalization ("Leite" == " leite ").
  def pending_item_named(name)
    key = TextNormalizer.call(name)
    list_items.on_list.pending.find { |item| TextNormalizer.call(item.name) == key }
  end

  # Deletes in one query and broadcasts once, instead of once per item.
  def clear_purchased!
    list_items.purchased.delete_all
    broadcast_list
  end

  # "Compra feita": records a ShoppingTrip and archives the purchased items on
  # the list into it, keeping them as history. Pending items stay on the list.
  # Returns the new trip, or nil (and creates nothing) if nothing was purchased.
  # One UPDATE and one broadcast, instead of one per item (update_all skips the
  # per-item callbacks).
  def finish_shopping!
    now = Time.current
    trip = transaction do
      trip = shopping_trips.create!(finished_at: now)
      archived = list_items.on_list.purchased.update_all(shopping_trip_id: trip.id, updated_at: now)
      raise ActiveRecord::Rollback if archived.zero?

      trip
    end

    broadcast_list if trip
    trip
  end

  # Re-render the whole grouped list and its summary for every client on this
  # household's stream: a change can add or empty a category section, not just
  # touch one row. Both go in a single broadcast.
  def broadcast_list
    Turbo::StreamsChannel.broadcast_render_to self, partial: "list_items/refresh",
      locals: { groups: list_items_by_category }
  end

  # [[category, items], ...] ordered by category position; uncategorized last.
  # Only categories with at least one item show up. Within a category, pending
  # items come first so what is still missing stays at the top.
  def list_items_by_category
    list_items.on_list.includes(:category).order(:status, :created_at, :id)
      .group_by(&:category)
      .sort_by { |category, _| category&.position || Float::INFINITY }
  end
end
