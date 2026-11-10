module SyncCommands
  # `set_item_state`: the *desired* state of an item, never a toggle.
  #
  #   payload:       { "public_id": "<uuid>", "desired": "purchased" | "pending" }
  #   preconditions: { "lock_version": 4 }          (when it does not inherit one)
  #
  # An item already in the desired state is a `noop`, so a resend, or a second
  # phone asking for the same state, never flips it back. Archived or removed
  # items answer `gone` and are never recreated here.
  #
  # Which version must the item have? The one the command's author could have
  # seen:
  #
  # - If the command it `depends_on` touched this item, the version that
  #   command left (recorded in its ledger result) is inherited. The author of
  #   a queued add → mark → unmark sequence cannot know the server's versions
  #   in advance, and the command already sent is never rewritten; the server
  #   reads them from the result of the previous step instead. A newer snapshot
  #   may have arrived while that receipt was still unacknowledged; use the
  #   greatest of the inherited and explicitly observed versions.
  # - Otherwise `preconditions.lock_version` is required: the version of the
  #   item as the phone last saw it in a snapshot.
  #
  # A mismatch is a conflict (`precondition_failed`) carrying the current state;
  # nothing is overwritten. `observed_revision` does not decide anything here.
  class SetItemState
    DESIRED = %w[pending purchased].freeze

    def self.call(command) = new(command).call

    def initialize(command)
      @command = command
      @household = command.household
    end

    def call
      public_id = uuid(@command.payload["public_id"])
      desired = @command.payload["desired"]
      return malformed("payload.public_id") unless public_id
      return malformed("payload.desired") unless DESIRED.include?(desired)

      found = ItemLookup.new(@household).find(public_id)
      return rejected("not_found") if found.unknown?
      return rejected("gone", "reason" => found.kind.to_s, "canonical_public_id" => found.canonical_public_id) if found.gone?

      item = found.item
      explicit = @command.preconditions["lock_version"]
      return malformed("preconditions.lock_version") unless explicit.nil? || (explicit.is_a?(Integer) && explicit >= 0)

      expected = [ ItemData.inherited_lock_version(@command, item), explicit ].compact.max
      return malformed("preconditions.lock_version") unless expected.is_a?(Integer) && expected >= 0

      return SyncCommandProcessor::Outcome.noop("item" => ItemData.entry(item, public_id)) if item.status == desired
      return conflict(item, public_id, "version_changed", expected) unless item.lock_version == expected

      change(item, public_id, expected)
    end

    private

    def change(item, public_id, expected)
      # The same transition as the PWA checkbox: pending ↔ purchased, with its
      # purchased_at, and a purchased item cannot come back beside a pending
      # item of the same name.
      return conflict(item, public_id, "duplicate_pending", expected) unless item.toggle_purchased!(sync: false)

      @household.record_shared_change!
      SyncCommandProcessor::Outcome.applied("item" => ItemData.entry(item, public_id))
    rescue ListItem::StaleAction, ActiveRecord::StaleObjectError
      conflict(item.reload, public_id, "version_changed", expected)
    end

    def conflict(item, public_id, reason, expected)
      SyncCommandProcessor::Outcome.conflict("precondition_failed",
        "reason" => reason, "expected_lock_version" => expected, "current" => ItemData.entry(item, public_id))
    end

    def rejected(error, data = {}) = SyncCommandProcessor::Outcome.rejected(error, data)

    def malformed(field) = rejected("malformed_command", "field" => field)

    def uuid(value)
      value.downcase if value.is_a?(String) && SyncCommand::UUID.match?(value)
    end
  end
end
