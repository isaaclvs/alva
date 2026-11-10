module SyncCommands
  # How a command reports an item, and how a later command reads that back.
  #
  # Every entry carries `public_id` (the UUID the command cited),
  # `canonical_public_id` (the server's record; different when the cited UUID is
  # an alias) and the state the item has *after* the command, including the
  # `lock_version` that a dependent command inherits (see
  # SyncCommands::SetItemState).
  module ItemData
    module_function

    def entry(item, cited_public_id, **extra)
      { "public_id" => cited_public_id, "canonical_public_id" => item.public_id, "name" => item.name,
        "status" => item.status, "purchased_at" => item.purchased_at&.utc&.iso8601(6),
        "lock_version" => item.lock_version }.merge(extra.stringify_keys)
    end

    # The lock_version the command named by `depends_on` left on `item`, when
    # that command touched it; nil otherwise (no dependency, or it was about
    # other items). Matched by the canonical record, so it does not matter
    # whether the two commands cited the item by its UUID or by an alias.
    def inherited_lock_version(command, item)
      data = command.dependency&.data
      return unless data.is_a?(Hash)

      entries = Array(data["items"]) + [ data["item"] ]
      entry = entries.find { |candidate| candidate.is_a?(Hash) && candidate["canonical_public_id"] == item.public_id }
      version = entry && entry["lock_version"]
      version if version.is_a?(Integer)
    end
  end
end
