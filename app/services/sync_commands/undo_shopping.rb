module SyncCommands
  # `undo_shopping`: the sync counterpart of the toast "Desfazer" after "Compra
  # feita" (Household#undo_shopping!). The trip's items go back to the list as
  # they were, purchased and with name, category and purchased_at intact, and
  # the trip is deleted.
  #
  #   payload:    { "trip_public_id": "<uuid>" }   (optional with depends_on)
  #   depends_on: the finish_shopping command that made the trip (optional)
  #
  # With `depends_on`, the trip is the one that command's receipt recorded and
  # the command stays referenced in the result (`finish_id`). A `trip_public_id`
  # given as well must be the same trip. Preconditions, all checked under the
  # household lock: the trip belongs to this household, still exists, and is the
  # most recent one. No time limit applies.
  #
  # An item typed again on the list after the finish ("Leite") stays where it
  # is and the archived copy is dropped, as in the PWA; here the dropped UUID
  # (and the aliases that pointed at it) are redirected to the item that stayed,
  # so a phone that still holds the old identity keeps resolving it.
  class UndoShopping
    def self.call(command) = new(command).call

    def initialize(command)
      @command = command
      @household = command.household
    end

    def call
      trip_id = trip_public_id
      return trip_id if trip_id.is_a?(SyncCommandProcessor::Outcome)

      trip = @household.shopping_trips.find_by(public_id: trip_id)
      return missing(trip_id) unless trip

      latest = @household.latest_shopping_trip
      return not_latest(trip, latest) unless trip == latest

      restore(trip)
    end

    private

    def restore(trip)
      redirects = []
      item_ids = trip.list_items.pluck(:id)
      undone = @household.undo_shopping!(trip, on_dropped: ->(pairs) { redirects = redirect(pairs) })
      return not_latest(trip, @household.latest_shopping_trip) unless undone

      restored = @household.list_items.on_list.where(id: item_ids).order(:id)
      data = { "trip_public_id" => trip.public_id,
               "items" => restored.map { |item| ItemData.entry(item, item.public_id) },
               "dropped" => redirects }
      data["finish_id"] = @command.depends_on if @command.depends_on
      SyncCommandProcessor::Outcome.applied(data)
    end

    # The archived duplicate disappears; its identity now means the item that
    # stayed. Returns what the receipt reports.
    def redirect(pairs)
      pairs.map do |dropped, kept|
        SyncAlias.where(household_id: @household.id, canonical_public_id: dropped.public_id)
          .update_all(canonical_public_id: kept.public_id)
        SyncAlias.create!(household: @household, public_id: dropped.public_id, canonical_public_id: kept.public_id,
          command_id: @command.id)
        { "public_id" => dropped.public_id, "canonical_public_id" => kept.public_id }
      end
    end

    # The trip's UUID, or a rejected Outcome.
    def trip_public_id
      explicit = @command.payload["trip_public_id"]
      return malformed("payload.trip_public_id") unless explicit.nil? || uuid(explicit)

      receipt = @command.dependency
      if @command.depends_on
        return malformed("depends_on") unless receipt&.command_type == "finish_shopping" && receipt.status == "applied"

        original = receipt.data.dig("trip", "public_id")
        return malformed("payload.trip_public_id") if explicit && uuid(explicit) != original

        return original
      end

      explicit ? uuid(explicit) : malformed("payload.trip_public_id")
    end

    # Already undone (its tombstone) answers gone; anything else, including a
    # trip of another household, answers the same not_found.
    def missing(trip_id)
      if SyncTombstone.exists?(household_id: @household.id, public_id: trip_id, record_type: "ShoppingTrip")
        rejected("gone", "reason" => "undone", "trip_public_id" => trip_id)
      else
        rejected("not_found")
      end
    end

    def not_latest(trip, latest)
      SyncCommandProcessor::Outcome.conflict("precondition_failed",
        "reason" => "not_latest", "trip_public_id" => trip.public_id, "latest_trip_public_id" => latest&.public_id)
    end

    def rejected(error, data = {}) = SyncCommandProcessor::Outcome.rejected(error, data)

    def malformed(field) = rejected("malformed_command", "field" => field)

    def uuid(value)
      value.downcase if value.is_a?(String) && SyncCommand::UUID.match?(value)
    end
  end
end
