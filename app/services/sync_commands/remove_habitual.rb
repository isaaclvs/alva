module SyncCommands
  # `remove_habitual`: takes a name out of the household's habitual items.
  #
  #   payload: { "public_id": "<uuid of the habitual>" }
  #
  # Only the habitual goes (and leaves a tombstone): items on the list and the
  # history are untouched, as in HabitualItemsController#destroy. The command
  # states a desired end state, like set_item_state: a habitual this household
  # already removed is a `noop` with `outcome: "already_removed"`, so two phones
  # removing the same name do not fight. A UUID that is not a habitual of this
  # household (unknown, another household's, or another kind of record) is
  # `rejected` / `not_found`, with the same answer for all three.
  class RemoveHabitual
    def self.call(command) = new(command).call

    def initialize(command)
      @command = command
      @household = command.household
    end

    def call
      public_id = HabitualData.uuid(@command.payload["public_id"])
      return HabitualData.malformed("payload.public_id") unless public_id

      habitual = @household.habitual_items.find_by(public_id:)
      if habitual
        @household.remove_habitual_item(habitual)
        @household.broadcast_habitual_items_after_commit
        return SyncCommandProcessor::Outcome.applied("outcome" => "removed", "item" => HabitualData.entry(habitual))
      end
      return SyncCommandProcessor::Outcome.noop("outcome" => "already_removed", "public_id" => public_id) if removed?(public_id)

      SyncCommandProcessor::Outcome.rejected("not_found")
    end

    private

    def removed?(public_id)
      SyncTombstone.exists?(household_id: @household.id, record_type: HabitualItem.name, public_id:)
    end
  end
end
