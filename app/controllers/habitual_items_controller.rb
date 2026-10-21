class HabitualItemsController < ApplicationController
  def create
    @household = current_household
    @habitual_item = HabitualItem.new(name: habitual_item_params[:name])
    @habitual_item.household = @household
    @household.with_list_lock { @habitual_item.save }

    if @habitual_item.persisted?
      @notice = "#{@habitual_item.name} salvo nos habituais"
      @household.broadcast_habitual_items
      respond_with_form(toast_message: @notice)
    else
      @notice = habitual_item_error
      respond_with_form status: :unprocessable_entity
    end
  rescue ActiveRecord::RecordNotUnique
    @habitual_item = HabitualItem.new(name: habitual_item_params[:name], household: @household)
    @notice = "Esse hábito já está salvo"
    respond_with_form status: :unprocessable_entity
  end

  def destroy
    @household = current_household
    @habitual_item = @household.habitual_items.find_by(id: params[:id])
    return head :not_found unless @habitual_item

    @household.with_list_lock { @habitual_item.destroy! }
    @notice = "#{@habitual_item.name} removido dos habituais"
    @household.broadcast_habitual_items
    respond_with_feedback(toast_message: @notice)
  end

  def add_selected
    @household = current_household
    add_items { selected_habits }
  rescue InvalidHabitSelection
    @notice = "Selecione hábitos válidos desta casa"
    respond_with_panel status: :unprocessable_entity, toast_message: @notice
  end

  def add_all
    @household = current_household
    add_items { @household.habitual_items.order(:normalized_name, :id).to_a }
  end

  private

  class InvalidHabitSelection < StandardError; end

  def selected_habits
    ids = params[:habitual_item_ids]
    raise InvalidHabitSelection unless ids.is_a?(Array) && ids.any?
    raise InvalidHabitSelection unless ids.all? { |id| id.to_s.match?(/\A[1-9]\d*\z/) }

    ids = ids.uniq
    habits = current_household.habitual_items.where(id: ids).order(:normalized_name, :id).to_a
    raise InvalidHabitSelection unless habits.length == ids.length

    habits
  end

  def add_items
    @household = current_household
    @results = @household.batch_list_broadcasts do
      @household.with_list_lock do
        habits = yield
        habits.map { |habit| @household.add_item(habit.name) }
      end
    end
    if @results.empty?
      @notice = "Ainda não há itens habituais salvos"
      return respond_with_panel status: :unprocessable_entity, toast_message: @notice
    end
    added = @results.count { |result| %i[created revived].include?(result.outcome) }
    already_there = @results.count { |result| result.outcome == :duplicate }
    @notice = if already_there.positive?
      "#{added} adicionados, #{already_there} já estavam na lista"
    else
      "#{added} adicionados à lista"
    end
    respond_with_panel(list: true, toast_message: @notice)
  end

  def habitual_item_params
    params.expect(habitual_item: [ :name ])
  end

  def habitual_item_error
    if @habitual_item.errors.of_kind?(:normalized_name, :taken)
      "Esse item habitual já está salvo"
    elsif @habitual_item.errors.of_kind?(:name, :too_long)
      "O item habitual deve ter até 100 caracteres"
    else
      "Digite o nome do item habitual"
    end
  end

  def respond_with_panel(list: false, status: :ok, toast_message: nil)
    streams = list ? [ list_refresh_stream ] : []
    streams << turbo_stream.update("toast", partial: "habitual_items/notice", locals: { message: toast_message }) if toast_message

    respond_to do |format|
      format.turbo_stream { render turbo_stream: streams, status: status }
      format.html { redirect_to root_path, status: :see_other, notice: @notice }
    end
  end

  def respond_with_form(status: :ok, toast_message: nil)
    form = if status == :unprocessable_entity
      turbo_stream.replace("habitual_item_form", partial: "habitual_items/form",
        locals: { habitual_item: @habitual_item, message: @notice })
    else
      turbo_stream.replace("habitual_item_form", partial: "habitual_items/form",
        locals: { habitual_item: HabitualItem.new, message: nil })
    end
    streams = [ form ]
    streams << turbo_stream.update("toast", partial: "habitual_items/notice", locals: { message: toast_message }) if toast_message

    respond_to do |format|
      format.turbo_stream { render turbo_stream: streams, status: status }
      format.html { redirect_to root_path, status: :see_other, notice: @notice }
    end
  end

  def respond_with_feedback(toast_message:)
    respond_to do |format|
      format.turbo_stream do
        render turbo_stream: turbo_stream.update("toast", partial: "habitual_items/notice",
          locals: { message: toast_message })
      end
      format.html { redirect_to root_path, status: :see_other, notice: toast_message }
    end
  end
end
