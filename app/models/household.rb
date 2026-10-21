class Household < ApplicationRecord
  has_many :list_items
  has_many :shopping_trips
  has_many :habitual_items, dependent: :destroy

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

  # SQLite ignores row-level SELECT ... FOR UPDATE. A harmless UPDATE obtains
  # its database write lock, serializing state transitions for this household.
  def with_list_lock
    transaction do
      self.class.where(id: id).update_all("updated_at = updated_at")
      yield
    end
  end

  # The single entry point for putting a name on the list. Returns an AddResult:
  # - :created   a new pending item was saved;
  # - :revived   the name matched a purchased item, which is pending again
  #              (keeping its original name); no new row;
  # - :duplicate the name is already pending; `item` is that existing item;
  # - :invalid   nothing saved; `item.errors` says why (e.g. blank name).
  def add_item(name)
    with_list_lock do
      existing = pending_item_named(name)
      next AddResult.new(existing, :duplicate) if existing

      purchased = purchased_item_named(name)
      if purchased
        purchased.toggle_purchased!
        next AddResult.new(purchased, :revived)
      end

      item = list_items.new(name: name)
      begin
        AddResult.new(item, item.save ? :created : :invalid)
      rescue ActiveRecord::RecordNotUnique
        existing = pending_item_named(name)
        AddResult.new(existing || item, existing ? :duplicate : :invalid)
      end
    end
  end

  # Adds every name typed in the add field (see ItemNameSplitter for the
  # separators and limits), each one through add_item, and returns their
  # AddResults in order. The list is broadcast once for the whole batch.
  # Text with no names in it counts as one invalid attempt, like a blank name.
  def add_items(text)
    names = ItemNameSplitter.call(text).presence || [ "" ]
    batch_list_broadcasts { with_list_lock { names.map { |name| add_item(name) } } }
  end

  # "Compra feita": records a ShoppingTrip and archives the purchased items on
  # the list into it, keeping them as history. Pending items stay on the list.
  # Returns the new trip, or nil (and creates nothing) if nothing was purchased.
  # One UPDATE and one broadcast, instead of one per item (update_all skips the
  # per-item callbacks).
  def finish_shopping!
    trip = with_list_lock do
      now = Time.current
      trip = shopping_trips.create!(finished_at: now)
      archived = list_items.on_list.purchased.update_all(shopping_trip_id: trip.id, updated_at: now)
      raise ActiveRecord::Rollback if archived.zero?

      trip
    end

    broadcast_list if trip
    trip
  end

  # Undo of "Compra feita": puts the trip's items back on the list as they were
  # (still purchased) and deletes the trip, in one transaction and with one
  # broadcast. Only the most recent trip can be undone; returns false (and
  # changes nothing) for any other. Items already back on the list are not
  # duplicated.
  def undo_shopping!(trip)
    undone = with_list_lock do
      next false unless trip.household_id == id && trip == latest_shopping_trip

      # An item put on the list again after the trip ("Leite" typed meanwhile)
      # keeps its entry and the archived copy goes away, so it never shows up
      # twice; same idea as typing a bought item bringing it back.
      trip.list_items.select { |item| item_named(list_items.on_list, item.name) }.each(&:delete)
      trip.list_items.update_all(shopping_trip_id: nil, updated_at: Time.current)
      trip.destroy!
      true
    end

    broadcast_list if undone
    undone
  end

  def latest_shopping_trip
    shopping_trips.order(finished_at: :desc, id: :desc).first
  end

  # Undo of a removal: puts the item described by a ListItemSnapshot back with
  # its original attributes (id and created_at included, so it returns to the
  # same place). Returns the restored item, or nil when there is nothing to do:
  # the snapshot is from another household, the item is already back, or a
  # pending item with the same name was added meanwhile.
  def restore_item(snapshot)
    return unless snapshot["household_id"] == id

    with_list_lock do
      next if ListItem.exists?(snapshot["id"]) || pending_item_named(snapshot["name"])

      list_items.create!(snapshot.slice(*ListItemSnapshot::ATTRIBUTES - %w[household_id]))
    rescue ActiveRecord::RecordNotUnique
      nil
    end
  end

  # Re-render the whole grouped list, its summary and the "buy again" chips for
  # every client on this household's stream: a change can add or empty a
  # category section, not just touch one row, and any change can alter what is
  # worth suggesting. All of it goes in a single broadcast.
  def broadcast_list
    Turbo::StreamsChannel.broadcast_render_to self, partial: "list_items/refresh",
      locals: { groups: list_items_by_category, suggestions: BuyAgainSuggestions.call(self), household: self }
  end

  # Habits have their own stream target so a list refresh does not reset the
  # disclosure state or checkboxes in the household's management section.
  def broadcast_habitual_items
    Turbo::StreamsChannel.broadcast_render_to self, partial: "habitual_items/refresh",
      locals: { household: self }
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
