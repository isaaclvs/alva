module SyncCommands
  # Removes a live item only when its observed version still matches. The
  # receipt keeps the attributes needed by a later undo_remove command.
  class RemoveItem
    def self.call(command) = new(command).call

    def initialize(command)
      @command = command
      @household = command.household
    end

    def call
      public_id = @command.payload["public_id"]
      public_id = public_id.downcase if public_id.is_a?(String) && SyncCommand::UUID.match?(public_id)
      return rejected("malformed_command", "field" => "payload.public_id") unless public_id

      found = ItemLookup.new(@household).find(public_id)
      return rejected("not_found") if found.unknown?
      return rejected("gone", "reason" => found.kind.to_s,
        "canonical_public_id" => found.canonical_public_id) if found.gone?

      item = found.item
      explicit = @command.preconditions["lock_version"]
      return rejected("malformed_command", "field" => "preconditions.lock_version") unless
        explicit.nil? || (explicit.is_a?(Integer) && explicit >= 0)

      expected = [ ItemData.inherited_lock_version(@command, item), explicit ].compact.max
      return rejected("malformed_command", "field" => "preconditions.lock_version") unless expected.is_a?(Integer) && expected >= 0
      return SyncCommandProcessor::Outcome.conflict("precondition_failed",
        "reason" => "version_changed", "expected_lock_version" => expected,
        "current" => ItemData.entry(item, public_id)) unless item.lock_version == expected

      receipt = { "item" => ItemData.entry(item, public_id).merge(
        "category_id" => item.category_id, "created_at" => item.created_at.utc.iso8601(6),
        "position" => item.position) }
      @household.remove_item(item)
      SyncCommandProcessor::Outcome.applied(receipt)
    rescue ActiveRecord::StaleObjectError
      SyncCommandProcessor::Outcome.conflict("precondition_failed", "reason" => "version_changed")
    end

    private

    def rejected(error, data = {}) = SyncCommandProcessor::Outcome.rejected(error, data)
  end
end
