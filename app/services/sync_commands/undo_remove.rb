module SyncCommands
  # Compensates exactly the removal named by depends_on. The processor has
  # already restricted that receipt to this household and credential lineage.
  # No client-provided item attributes, timestamp or expiring web token is used.
  class UndoRemove
    def self.call(command) = new(command).call

    def initialize(command)
      @command = command
      @household = command.household
    end

    def call
      removal = @command.dependency
      return rejected("malformed_command", "field" => "depends_on") unless @command.depends_on
      return rejected("invalid_removal") unless removal&.command_type == "remove_item" && removal.status == "applied"

      original = removal.data.fetch("item")
      canonical = original.fetch("canonical_public_id")
      found = ItemLookup.new(@household).find(canonical)
      return conflict("item_present") if found.live?
      return conflict("item_archived") if found.kind == :archived

      # The household revision distinguishes successive removals of the same
      # identity, including a restore/removal through the PWA in the meantime.
      tombstone = SyncTombstone.find_by(household_id: @household.id, public_id: canonical,
        record_type: "ListItem", scope: SyncTombstone::HOUSEHOLD)
      return conflict("removal_changed") unless tombstone&.revision == removal.revision
      return conflict("duplicate_pending") if @household.pending_item_named(original.fetch("name"))

      category = Category.find_by(public_id: original["category_public_id"]) if original["category_public_id"]
      return conflict("category_missing") if original["category_public_id"] && !category

      restore(original, category)
    end

    private

    def restore(original, category)
      item = @household.list_items.new(original.slice("name", "status", "purchased_at", "created_at", "updated_at", "position"))
      item.public_id = original.fetch("canonical_public_id")
      item.category = category
      # Never reuse the removed version: queued edits from before the deletion
      # must conflict, while edits depending on this receipt inherit this one.
      item.lock_version = original.fetch("lock_version") + 1
      item.save!
      # before_create categorizes an absent category. A compensation preserves
      # even a legacy nil category instead of consulting today's dictionary.
      item.update_columns(category_id: nil) unless category
      SyncTombstone.forget!(item.public_id)
      @household.record_shared_change!
      SyncCommandProcessor::Outcome.applied("removal_id" => @command.depends_on,
        "item" => ItemData.entry(item, original.fetch("public_id"), category_public_id: category&.public_id))
    end

    def rejected(error, data = {}) = SyncCommandProcessor::Outcome.rejected(error, data)

    def conflict(reason)
      SyncCommandProcessor::Outcome.conflict("precondition_failed", "reason" => reason)
    end
  end
end
