module SyncCommands
  # The processor already holds SQLite's database write lock before calling
  # executors. The dictionary observation and learning therefore serialize even
  # when two commands belong to different households; no synthetic revision is
  # needed to acquire another lock.
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
      return malformed("payload.category_id") unless category_id.nil? || uuid(category_id)

      explicit = @command.preconditions["lock_version"]
      return malformed("preconditions.lock_version") unless explicit.nil? || valid_revision?(explicit)
      dictionary_revision = @command.preconditions["dictionary_revision"]
      return malformed("preconditions.dictionary_revision") if category_id && !valid_revision?(dictionary_revision)

      found = ItemLookup.new(@household).find(public_id)
      return rejected("not_found") if found.unknown?
      return rejected("gone", "reason" => found.kind.to_s, "canonical_public_id" => found.canonical_public_id) if found.gone?
      item = found.item
      expected = [ ItemData.inherited_lock_version(@command, item), explicit ].compact.max
      return malformed("preconditions.lock_version") unless valid_revision?(expected)
      return conflict(item, public_id, "version_changed", expected) unless item.lock_version == expected

      category = category_id && Category.find_by(public_id: category_id.downcase)
      return rejected("not_found", "field" => "payload.category_id") if category_id && !category
      current_dictionary = SyncScope.revision_for(SyncScope::DICTIONARY)
      learning_pending = category && current_dictionary != dictionary_revision

      item.name = name.squish
      if category
        item.category = category
      elsif item.will_save_change_to_name?
        item.category = ItemCategorizer.call(item.name)
      end
      return conflict(item.reload, public_id, "duplicate_pending", expected) unless item.save

      item_changed = item.saved_changes.any?
      learned = ItemCategorizer.learn(item.name, category) if category && !learning_pending
      dictionary_changed = learned.present? && learned.saved_changes.any?
      changed = item_changed || dictionary_changed
      @household.record_shared_change!(dictionary_changed: dictionary_changed) if changed
      data = { "item" => item_data(item, public_id), "learned" => !!dictionary_changed,
        "dictionary_revision" => SyncScope.revision_for(SyncScope::DICTIONARY) }
      if learning_pending
        data["learning_pending"] = { "category_id" => category.public_id, "dictionary_revision" => current_dictionary }
      end
      changed ? SyncCommandProcessor::Outcome.applied(data) : SyncCommandProcessor::Outcome.noop(data)
    end

    private

    def valid_revision?(value) = value.is_a?(Integer) && value >= 0
    def uuid(value)
      value.downcase if value.is_a?(String) && SyncCommand::UUID.match?(value)
    end
    def rejected(error, data = {}) = SyncCommandProcessor::Outcome.rejected(error, data)
    def malformed(field) = rejected("malformed_command", "field" => field)
    def item_data(item, public_id) = ItemData.entry(item, public_id, category_public_id: item.category&.public_id)

    def conflict(item, public_id, reason, expected)
      SyncCommandProcessor::Outcome.conflict("precondition_failed", "reason" => reason,
        "expected_lock_version" => expected, "current" => item_data(item, public_id))
    end
  end
end
