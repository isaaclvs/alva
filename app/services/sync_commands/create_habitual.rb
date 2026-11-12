module SyncCommands
  # `create_habitual`: saves a name among the household's habitual items.
  #
  #   payload: { "name": "Café" }
  #
  # Same rules as HabitualItemsController#create: the name is squished and
  # identity is the normalized name, so " cafe " is the habitual "Café" that is
  # already saved. That case is a `noop` with `outcome: "duplicate"` and the
  # existing record, which makes a resend or a second phone harmless. A blank
  # name or one over HabitualItem's limit is `rejected` / `invalid_name`
  # (`data.reason`: `blank` or `too_long`).
  #
  # Habitual items are server-identified: the UUID comes back in `data.item`.
  class CreateHabitual
    def self.call(command) = new(command).call

    def initialize(command)
      @command = command
      @household = command.household
    end

    def call
      name = @command.payload["name"]
      return HabitualData.malformed("payload.name") unless name.is_a?(String)

      name = name.squish
      return invalid("blank") if name.blank?
      return invalid("too_long") if name.length > HabitualItem::MAX_NAME_LENGTH

      existing = @household.habitual_items.find_by(normalized_name: TextNormalizer.call(name))
      return SyncCommandProcessor::Outcome.noop(data(existing, "duplicate")) if existing

      habitual = @household.add_habitual_item(name)
      return invalid("blank") unless habitual.persisted?

      @household.broadcast_habitual_items_after_commit
      SyncCommandProcessor::Outcome.applied(data(habitual, "created"))
    end

    private

    def data(habitual, outcome) = { "outcome" => outcome, "item" => HabitualData.entry(habitual) }

    def invalid(reason) = SyncCommandProcessor::Outcome.rejected("invalid_name", "reason" => reason)
  end
end
