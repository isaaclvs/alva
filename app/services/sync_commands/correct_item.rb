module SyncCommands
  # Corrects a live item. Manual learning is conditional on the dictionary
  # revision observed by the client; a stale dictionary never gets overwritten.
  class CorrectItem
    def self.call(command) = new(command).call

    def initialize(command)
      @command = command
      @household = command.household
    end

    def call
      public_id = uuid(@command.payload["public_id"])
      name = @command.payload["name"]
      category_id = @command.payload["category_id"]
      return malformed("payload.public_id") unless public_id
      return malformed("payload.name") unless name.is_a?(String)
      return rejected("invalid_name") if name.squish.blank?
      return malformed("payload.category_id") if !category_id.nil? && !uuid(category_id)

      found = ItemLookup.new(@household).find(public_id)
      return rejected("not_found") if found.unknown?
      return rejected("gone", "reason" => found.kind.to_s, "canonical_public_id" => found.canonical_public_id) if found.gone?
      item = found.item
      category = category_id && Category.find_by(public_id: category_id.downcase)
      return rejected("not_found", "field" => "payload.category_id") if category_id && !category
      explicit = @command.preconditions["lock_version"]
      inherited = ItemData.inherited_lock_version(@command, item)
      expected = [ inherited, explicit ].compact.max
      return malformed("preconditions.lock_version") unless expected.is_a?(Integer) && expected >= 0
      manual = category && category.id != item.category_id
      if manual
        revision = @command.preconditions["dictionary_revision"]
        return malformed("preconditions.dictionary_revision") unless revision.is_a?(Integer) && revision >= 0
      end
      return conflict(item, public_id, "version_changed", expected) unless item.lock_version == expected
      return conflict(item, public_id, "duplicate_pending", expected) if @household.pending_item_named(name.squish) && @household.pending_item_named(name.squish).id != item.id

      @household.with_list_lock do
        item.reload
        return conflict(item, public_id, "version_changed", expected) unless item.lock_version == expected && item.shopping_trip_id.nil?
        current_dictionary = SyncScope.revision_for(SyncScope::DICTIONARY)
        learning_pending = manual && current_dictionary != revision
        picked = learning_pending ? nil : category
        old_category = item.category_id
        old_name = item.name
        item.name = name.squish
        item.category = picked if picked
        item.category = ItemCategorizer.call(item.name) if !picked && item.will_save_change_to_name?
        return conflict(item, public_id, "duplicate_pending", expected) if @household.pending_item_named(item.name) && @household.pending_item_named(item.name).id != item.id
        return rejected("invalid_name") unless item.save
        learned = picked && ItemCategorizer.learn(item.name, picked)
        changed = item.saved_changes? || old_name != item.name || old_category != item.category_id
        @household.record_shared_change!(dictionary_changed: learned&.saved_changes&.any?) if changed
        data = { "item" => ItemData.entry(item, public_id) }
        data["learning_pending"] = { "category_id" => category.public_id, "dictionary_revision" => current_dictionary } if learning_pending
        changed ? SyncCommandProcessor::Outcome.applied(data) : SyncCommandProcessor::Outcome.noop(data)
      end
    rescue ActiveRecord::StaleObjectError, ListItem::StaleAction
      conflict(item.reload, public_id, "version_changed", expected)
    end

    private
    def uuid(value)
      value.downcase if value.is_a?(String) && SyncCommand::UUID.match?(value)
    end
    def rejected(error, data = {}) = SyncCommandProcessor::Outcome.rejected(error, data)
    def malformed(field) = rejected("malformed_command", "field" => field)
    def conflict(item, public_id, reason, expected)
      SyncCommandProcessor::Outcome.conflict("precondition_failed", "reason" => reason,
        "expected_lock_version" => expected, "current" => ItemData.entry(item, public_id))
    end
  end
end
