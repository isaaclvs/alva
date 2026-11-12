module SyncCommands
  # `add_habituals`: puts habitual items on the list, the selected ones or all.
  #
  #   payload: { "public_ids": ["<uuid>", ...] }   # the selection
  #   payload: { "all": true }                      # every habitual
  #
  # Exactly one of the two. It is HabitualItemsController#add_selected / #add_all:
  # the names come from the household's habitual items (never from the payload),
  # ordered by normalized name, and go through Household#add_item_names, so the
  # list rules are the PWA's — categorization, duplicates, and a purchased item
  # of that name coming back — with one revision for the batch and no limit of
  # 30 names. A selection with a UUID that is not a habitual of this household
  # is `rejected` / `not_found` (`data.public_ids` lists them) and adds nothing;
  # `all` with no habitual saved is `rejected` / `no_habituals`.
  #
  # `data.items`, in the order the names were added, reports the item each name
  # became: `habitual_public_id`, the item as ItemData.entry reports it (its own
  # UUID: the server mints them here) and `outcome` (`created`, `revived` or
  # `duplicate`; `invalid` when the list refused the name). `applied` when
  # something landed on the list, `noop` when every name was already pending.
  class AddHabituals
    def self.call(command) = new(command).call

    def initialize(command)
      @command = command
      @household = command.household
    end

    def call
      habituals = select
      return habituals if habituals.is_a?(SyncCommandProcessor::Outcome)

      results = @household.add_item_names(habituals.map(&:name))
      entries = habituals.zip(results).map { |habitual, result| entry(habitual, result) }
      data = { "items" => entries }
      changed = results.any? { |result| result.outcome.in?(%i[created revived]) }
      changed ? SyncCommandProcessor::Outcome.applied(data) : SyncCommandProcessor::Outcome.noop(data)
    end

    private

    # Habitual items in the order they are added, or the Outcome that stops the command.
    def select
      payload = @command.payload
      return HabitualData.malformed("payload") if payload.key?("public_ids") == payload.key?("all")
      return everything if payload.key?("all")

      ids = payload["public_ids"]
      return HabitualData.malformed("payload.public_ids") unless ids.is_a?(Array) && ids.any?

      ids = ids.map { |id| HabitualData.uuid(id) }
      return HabitualData.malformed("payload.public_ids") if ids.any?(&:nil?)

      ids = ids.uniq
      habituals = ordered.where(public_id: ids).to_a
      missing = ids - habituals.map(&:public_id)
      return SyncCommandProcessor::Outcome.rejected("not_found", "public_ids" => missing) if missing.any?

      habituals
    end

    def everything
      return HabitualData.malformed("payload.all") unless @command.payload["all"] == true

      habituals = ordered.to_a
      habituals.empty? ? SyncCommandProcessor::Outcome.rejected("no_habituals") : habituals
    end

    def ordered = @household.habitual_items.order(:normalized_name, :id)

    def entry(habitual, result)
      item = result.item
      return { "habitual_public_id" => habitual.public_id, "name" => habitual.name, "outcome" => "invalid" } unless item.persisted?

      ItemData.entry(item, item.public_id, outcome: result.outcome.to_s, habitual_public_id: habitual.public_id)
    end
  end
end
