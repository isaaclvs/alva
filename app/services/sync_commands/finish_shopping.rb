module SyncCommands
  # `finish_shopping` ("Compra feita"): archives exactly the purchased items the
  # device observed into one new ShoppingTrip, all or nothing.
  #
  #   payload: { "items": [ { "public_id": "<uuid or alias>", "lock_version": 3 }, … ] }
  #
  # It never archives "every purchased item that exists": a pending item, and an
  # item another device added or marked after this one looked, stay on the list.
  # Each entry carries the version the device saw (or inherits the one that
  # `depends_on` left, like SetItemState, taking the greatest). If any entry
  # fails its precondition, nothing is archived and no trip is created; the
  # result lists every entry to review under `data.problems`.
  #
  # A resend of the same UUID is a `duplicate` (the ledger receipt). The same
  # items under another UUID find them archived and answer `gone`, so two
  # finishes never produce an empty or a second trip.
  class FinishShopping
    MAX_ITEMS = 500

    def self.call(command) = new(command).call

    def initialize(command)
      @command = command
      @household = command.household
      @lookup = ItemLookup.new(@household)
      @seen = []
    end

    def call
      entries = @command.payload["items"]
      return malformed("payload.items") unless entries.is_a?(Array) && entries.size.between?(1, MAX_ITEMS)

      observed = []
      entries.each_with_index do |entry, index|
        parsed = parse(entry, index)
        return parsed if parsed.is_a?(SyncCommandProcessor::Outcome)

        observed << parsed
      end

      problems = []
      items = observed.each_with_index.filter_map { |entry, index| check(entry, index, problems) }
      return refuse(problems) if problems.any?

      trip = @household.finish_shopping!(only: items.map { |item, _| item.id })
      SyncCommandProcessor::Outcome.applied(
        "trip" => { "public_id" => trip.public_id, "finished_at" => trip.finished_at.utc.iso8601(6) },
        "items" => items.map { |item, cited| ItemData.entry(item.reload, cited, shopping_trip_public_id: trip.public_id) })
    end

    Observed = Data.define(:public_id, :explicit)

    private

    # Observed(public_id, explicit version) or a rejected Outcome.
    def parse(entry, index)
      return malformed("payload.items[#{index}]") unless entry.is_a?(Hash)

      value = entry["public_id"]
      return malformed("payload.items[#{index}].public_id") unless value.is_a?(String) && SyncCommand::UUID.match?(value)

      explicit = entry["lock_version"]
      return malformed("payload.items[#{index}].lock_version") unless explicit.nil? || (explicit.is_a?(Integer) && explicit >= 0)

      Observed.new(public_id: value.downcase, explicit:)
    end

    # The live, purchased item at the observed version, as [item, cited uuid];
    # otherwise records why not in `problems` and returns nil.
    def check(entry, index, problems)
      found = @lookup.find(entry.public_id)
      return problem(problems, index, entry, "rejected", "not_found") if found.unknown?
      return problem(problems, index, entry, "rejected", "gone", "reason" => found.kind.to_s,
        "canonical_public_id" => found.canonical_public_id) if found.gone?

      item = found.item
      # Two entries naming one item (its UUID and an alias) are a client error.
      return problem(problems, index, entry, "rejected", "malformed_command", "reason" => "repeated_item") if
        @seen.include?(item.id)
      @seen << item.id

      expected = [ ItemData.inherited_lock_version(@command, item), entry.explicit ].compact.max
      return problem(problems, index, entry, "rejected", "malformed_command", "field" => "payload.items[#{index}].lock_version") unless expected

      unless item.lock_version == expected
        return problem(problems, index, entry, "conflict", "precondition_failed", "reason" => "version_changed",
          "expected_lock_version" => expected, "current" => ItemData.entry(item, entry.public_id))
      end
      return problem(problems, index, entry, "conflict", "precondition_failed", "reason" => "not_purchased",
        "expected_lock_version" => expected, "current" => ItemData.entry(item, entry.public_id)) unless item.purchased?

      [ item, entry.public_id ]
    end

    def problem(problems, index, entry, status, error, detail = {})
      problems << { "status" => status, "error" => error, "index" => index, "public_id" => entry.public_id }.merge(detail)
      nil
    end

    # The first problem (in payload order) names the outcome; all of them are
    # listed for the review. Nothing was written.
    def refuse(problems)
      first = problems.first
      data = first.except("status", "error").merge("problems" => problems)
      if first["status"] == "conflict"
        SyncCommandProcessor::Outcome.conflict(first["error"], data)
      else
        SyncCommandProcessor::Outcome.rejected(first["error"], data)
      end
    end

    def malformed(field) = SyncCommandProcessor::Outcome.rejected("malformed_command", "field" => field)
  end
end
