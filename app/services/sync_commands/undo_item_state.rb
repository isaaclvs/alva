module SyncCommands
  # `undo_item_state`: puts an item back in the state it had before a mark or
  # unmark, the sync counterpart of the PWA's toast "Desfazer" (PurchaseUndo and
  # ListItem#undo_purchase!). The web token is replaced by explicit data:
  #
  #   payload:       { "public_id": "<uuid>", "status": "pending" | "purchased",
  #                    "purchased_at": "<ISO 8601>" | null }
  #   preconditions: { "lock_version": 4 }     (when it does not inherit one)
  #
  # `status` and `purchased_at` are the state to restore (what the phone saw
  # before it marked). `lock_version` is the version the mark left, which is
  # what makes the undo one-use and keeps it from overwriting a later edit,
  # purchase or archiving. It is inherited from `depends_on` exactly as in
  # SetItemState, so a phone can queue mark → undo without knowing versions.
  #
  # The item already being in the state to restore, with the same date, is a
  # `noop` whatever version was sent: the result the phone wanted exists.
  class UndoItemState
    STATUSES = SetItemState::DESIRED

    def self.call(command) = new(command).call

    def initialize(command)
      @command = command
      @household = command.household
    end

    def call
      public_id = uuid(@command.payload["public_id"])
      status = @command.payload["status"]
      return malformed("payload.public_id") unless public_id
      return malformed("payload.status") unless STATUSES.include?(status)

      purchased_at = parse_time(@command.payload["purchased_at"])
      return malformed("payload.purchased_at") if purchased_at == :invalid
      return malformed("payload.purchased_at") if status == "pending" && purchased_at

      found = ItemLookup.new(@household).find(public_id)
      return rejected("not_found") if found.unknown?
      return rejected("gone", "reason" => found.kind.to_s, "canonical_public_id" => found.canonical_public_id) if found.gone?

      item = found.item
      explicit = @command.preconditions["lock_version"]
      return malformed("preconditions.lock_version") unless explicit.nil? || (explicit.is_a?(Integer) && explicit >= 0)

      expected = [ ItemData.inherited_lock_version(@command, item), explicit ].compact.max
      return malformed("preconditions.lock_version") unless expected.is_a?(Integer) && expected >= 0

      return SyncCommandProcessor::Outcome.noop("item" => ItemData.entry(item, public_id)) if restored?(item, status, purchased_at)
      return conflict(item, public_id, "version_changed", expected) unless item.lock_version == expected

      undo(item, public_id, expected, status, purchased_at)
    end

    private

    def undo(item, public_id, expected, status, purchased_at)
      # undo_purchase! publishes the shared change itself, like the PWA path.
      restored = item.undo_purchase!("lock_version" => expected, "status" => status,
        "purchased_at" => purchased_at&.utc&.iso8601(6))
      # An unmarked item cannot come back as pending beside another pending
      # item of the same name.
      return conflict(item, public_id, "duplicate_pending", expected) unless restored

      SyncCommandProcessor::Outcome.applied("item" => ItemData.entry(item, public_id))
    rescue ListItem::StaleAction, ActiveRecord::StaleObjectError
      conflict(item.reload, public_id, "version_changed", expected)
    end

    def restored?(item, status, purchased_at)
      item.status == status && item.purchased_at&.utc&.iso8601(6) == purchased_at&.utc&.iso8601(6)
    end

    # nil, a Time, or :invalid. Only a full ISO 8601 timestamp counts.
    def parse_time(value)
      return if value.nil?
      return :invalid unless value.is_a?(String)

      Time.iso8601(value)
    rescue ArgumentError
      :invalid
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
