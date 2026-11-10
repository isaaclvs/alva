module SyncCommands
  # `add_items`: puts names on the list with the identity the phone generated
  # for each of them.
  #
  #   payload: { "items": [ { "public_id": "<uuid>", "name": "Leite",
  #                           "revives": { "public_id": "<uuid>", "lock_version": 3 } } ] }
  #
  # Names go through the rules of the app (Household#add_item_for_sync:
  # normalization, the dictionary's category, duplicate and revival), so a name
  # added from the phone lands where the same name typed in the PWA would.
  # When the server already has the name, the phone's UUID becomes an alias of
  # the canonical item (SyncAlias) instead of creating a second one.
  #
  # A name that matches a *purchased* item is only brought back when the phone
  # says which purchase it saw (`revives`, public_id + lock_version) and the
  # item is still exactly that; otherwise it is a conflict, so an old request
  # cannot silently revive a purchase someone else just made.
  #
  # The command is all or nothing: the processor rolls back every item, alias
  # and revision when any entry conflicts or is rejected.
  class AddItems
    MAX_ITEMS = ItemNameSplitter::MAX_NAMES

    Entry = Data.define(:public_id, :name, :revives)
    Revives = Data.define(:public_id, :lock_version)

    def self.call(command) = new(command).call

    def initialize(command)
      @command = command
      @household = command.household
      @lookup = ItemLookup.new(@household)
    end

    def call
      entries = parse
      return entries if entries.is_a?(SyncCommandProcessor::Outcome)

      changed = false
      reported = []
      entries.each_with_index do |entry, index|
        result = add(entry)
        return failure(result, index, entry) if result.is_a?(SyncCommandProcessor::Outcome)

        changed ||= result[:outcome].in?(%w[created revived])
        reported << result[:entry]
      end

      # One revision for the command, only when something landed on the list.
      @household.record_shared_change! if changed
      data = { "items" => reported }
      changed ? SyncCommandProcessor::Outcome.applied(data) : SyncCommandProcessor::Outcome.noop(data)
    end

    private

    # { entry:, outcome: } or an Outcome that stops the command.
    def add(entry)
      known = @lookup.find(entry.public_id)
      return already_known(entry, known) unless known.unknown?
      return rejected("public_id_conflict") if ItemLookup.claimed?(entry.public_id)

      pending = @household.pending_item_named(entry.name)
      return unified(entry, pending, "duplicate") if pending

      revival = check_revival(entry)
      return revival if revival

      result = @household.add_item_for_sync(entry.name, public_id: entry.public_id)
      case result.outcome
      when :invalid then rejected("invalid_name")
      when :duplicate then unified(entry, result.item, "duplicate")
      when :created then reported(entry, result.item, "created")
      else unified(entry, result.item, "revived")
      end
    end

    # The UUID is already this household's: a replay of the same addition under
    # a new command id answers with the item as it is; anything else is a
    # different record claiming a used identity, or one that left the list.
    def already_known(entry, known)
      return rejected("gone", "reason" => known.kind.to_s, "canonical_public_id" => known.canonical_public_id) if known.gone?
      return rejected("public_id_conflict") unless known.item.normalized_name == TextNormalizer.call(entry.name)

      reported(entry, known.item, "duplicate")
    end

    # A purchased item with this name only comes back if the phone says which
    # purchase it saw and that item is still exactly that. Returns an Outcome
    # (rejection or conflict) that stops the command, or nil to go ahead.
    def check_revival(entry)
      purchased = @household.purchased_item_named(entry.name)
      seen = entry.revives && @lookup.find(entry.revives.public_id)
      if seen&.gone?
        return rejected("gone", "reason" => seen.kind.to_s, "field" => "revives.public_id")
      elsif seen&.unknown?
        return rejected("not_found", "field" => "revives.public_id")
      end
      return unless purchased || seen

      if purchased && seen&.item == purchased
        expected = [ ItemData.inherited_lock_version(@command, purchased), entry.revives.lock_version ].compact.max
        return malformed("revives.lock_version") unless expected
        return if purchased.lock_version == expected
      end

      conflict(purchased || seen.item, seen ? "purchase_changed" : "purchase_not_observed")
    end

    def unified(entry, item, outcome)
      SyncAlias.create!(household: @household, public_id: entry.public_id, canonical_public_id: item.public_id,
        command_id: @command.id)
      reported(entry, item, outcome)
    end

    def reported(entry, item, outcome)
      { outcome: outcome, entry: ItemData.entry(item, entry.public_id, outcome: outcome) }
    end

    def rejected(error, data = {})
      SyncCommandProcessor::Outcome.rejected(error, data)
    end

    # The conflicting item is reported by its own UUID: the phone's new UUID
    # was not registered, since the command was rolled back.
    def conflict(item, reason)
      SyncCommandProcessor::Outcome.conflict("precondition_failed",
        "reason" => reason, "current" => ItemData.entry(item, item.public_id))
    end

    def failure(outcome, index, entry)
      data = outcome.data.merge("index" => index, "public_id" => entry.public_id)
      SyncCommandProcessor::Outcome.new(status: outcome.status, data: data, error: outcome.error)
    end

    # Entries, or a malformed_command / invalid_name Outcome.
    def parse
      items = @command.payload["items"]
      return malformed("payload.items") unless items.is_a?(Array) && items.size.between?(1, MAX_ITEMS)

      entries = items.each_with_index.map { |item, index| entry_from(item, index) }
      failed = entries.find { |entry| entry.is_a?(SyncCommandProcessor::Outcome) }
      return failed if failed
      return malformed("payload.items.public_id") if entries.map(&:public_id).uniq.size != entries.size

      entries
    end

    def entry_from(item, index)
      return malformed("payload.items[#{index}]") unless item.is_a?(Hash)

      public_id = uuid(item["public_id"])
      return malformed("payload.items[#{index}].public_id") unless public_id
      return malformed("payload.items[#{index}].name") unless item["name"].is_a?(String)

      name = item["name"].squish
      if name.blank?
        return SyncCommandProcessor::Outcome.rejected("invalid_name", "index" => index, "public_id" => public_id)
      end

      revives = revives_from(item["revives"], index)
      return revives if revives.is_a?(SyncCommandProcessor::Outcome)

      Entry.new(public_id:, name:, revives:)
    end

    def revives_from(value, index)
      return if value.nil?

      field = "payload.items[#{index}].revives"
      return malformed(field) unless value.is_a?(Hash)

      public_id = uuid(value["public_id"])
      version = value["lock_version"]
      return malformed(field) unless public_id && (version.nil? || (version.is_a?(Integer) && version >= 0))

      Revives.new(public_id:, lock_version: version)
    end

    def uuid(value)
      value.downcase if value.is_a?(String) && SyncCommand::UUID.match?(value)
    end

    def malformed(field)
      SyncCommandProcessor::Outcome.rejected("malformed_command", "field" => field)
    end
  end
end
