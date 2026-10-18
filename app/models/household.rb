class Household < ApplicationRecord
  has_many :list_items
  has_many :shopping_trips

  # Key in ActiveSupport::IsolatedExecutionState: { household_id => changed? }
  # for each household currently inside batch_list_broadcasts.
  BATCHES_KEY = :household_list_broadcast_batches

  validates :name, presence: true

  # Pending item whose name matches after normalization ("Leite" == " leite ").
  def pending_item_named(name)
    item_named(list_items.on_list.pending, name)
  end

  # Purchased item whose name matches after normalization; the most recently
  # purchased one when there are several.
  def purchased_item_named(name)
    item_named(list_items.on_list.purchased.order(purchased_at: :desc, id: :desc), name)
  end

  # What add_item did: `item` is the affected ListItem (the new, revived or
  # already pending one; unsaved with errors when invalid) and `outcome` is
  # one of :created, :revived, :duplicate or :invalid.
  AddResult = Struct.new(:item, :outcome)

  # The single entry point for putting a name on the list. Returns an AddResult:
  # - :created   a new pending item was saved;
  # - :revived   the name matched a purchased item, which is pending again
  #              (keeping its original name); no new row;
  # - :duplicate the name is already pending; `item` is that existing item;
  # - :invalid   nothing saved; `item.errors` says why (e.g. blank name).
  def add_item(name)
    existing = pending_item_named(name)
    return AddResult.new(existing, :duplicate) if existing

    purchased = purchased_item_named(name)
    if purchased
      purchased.toggle_purchased!
      return AddResult.new(purchased, :revived)
    end

    item = list_items.new(name: name)
    AddResult.new(item, item.save ? :created : :invalid)
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

  # A list item changed (called by ListItem's after_commit). Broadcasts right
  # away, unless inside batch_list_broadcasts, which defers it to the end.
  def list_changed
    batches = ActiveSupport::IsolatedExecutionState[BATCHES_KEY]
    if batches&.key?(id)
      batches[id] = true
    else
      broadcast_list
    end
  end

  # Runs the block with per-item broadcasts held back, then broadcasts the list
  # once if anything changed (also when the block raises after partial changes).
  # Per household and per thread/fiber, so other requests are unaffected;
  # nested calls join the outer batch. Returns the block's value.
  def batch_list_broadcasts
    batches = (ActiveSupport::IsolatedExecutionState[BATCHES_KEY] ||= {})
    return yield if batches.key?(id)

    batches[id] = false
    begin
      yield
    ensure
      changed = batches.delete(id)
      broadcast_list if changed
    end
  end

  # [[category, items], ...] ordered by category position; uncategorized last.
  # Only categories with at least one item show up. Within a category, pending
  # items come first so what is still missing stays at the top.
  def list_items_by_category
    list_items.on_list.includes(:category).order(:status, :created_at, :id)
      .group_by(&:category)
      .sort_by { |category, _| category&.position || Float::INFINITY }
  end

  private

  # The single place that matches a typed name against existing items, so any
  # scope on the live list (e.g. excluding archived items) goes in one spot.
  def item_named(scope, name)
    key = TextNormalizer.call(name)
    return if key.blank?

    scope.find { |item| TextNormalizer.call(item.name) == key }
  end
end
