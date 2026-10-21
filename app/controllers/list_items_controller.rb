class ListItemsController < ApplicationController
  def index
    @household = current_household
    @groups = @household.list_items_by_category
    @suggestions = BuyAgainSuggestions.call(@household)

    respond_to do |format|
      format.html { @list_item = ListItem.new }
      # Resync when the app comes back to the foreground (list_refresh_controller.js).
      format.turbo_stream { render partial: "refresh", locals: { groups: @groups, suggestions: @suggestions } }
    end
  end

  def create
    results = current_household.add_items(list_item_params[:name])
    return create_many(results) if results.many?

    result = results.first
    case result.outcome
    when :created, :revived
      @groups = current_household.list_items_by_category
      @suggestions = BuyAgainSuggestions.call(current_household)
      @revived_item = result.item if result.outcome == :revived
      respond_to do |format|
        format.turbo_stream
        format.html { redirect_to root_path }
      end
    when :duplicate
      existing = result.item
      render turbo_stream: [
        turbo_stream.replace("list_item_form", partial: "form",
          locals: { list_item: ListItem.new, notice: "#{existing.name} já está na lista" }),
        turbo_stream.replace(existing, partial: "list_item", locals: { list_item: existing, highlight: true })
      ]
    else
      render turbo_stream: turbo_stream.replace("list_item_form", partial: "form", locals: { list_item: result.item }),
             status: :unprocessable_entity
    end
  end

  # The row again, for "Cancelar" in the edit form. Both only make sense inside
  # the row's frame; opened directly they go back to the list.
  def show
    @list_item = current_household.list_items.on_list.find_by(id: params[:id])
    return missing_or_stale_frame unless @list_item

    redirect_to root_path unless turbo_frame_request?
  end

  # Fix a typo or a wrong category, in place of the row.
  def edit
    @list_item = current_household.list_items.on_list.find_by(id: params[:id])
    return missing_or_stale_frame unless @list_item

    redirect_to root_path unless turbo_frame_request?
  end

  def update
    correction = params.expect(list_item: [ :name, :category_id, :lock_version ])
    current_household.with_list_lock do
      @list_item = current_household.list_items.on_list.find_by(id: params[:id])
      return missing_or_stale_item unless @list_item
      return stale_list_action if stale_version?(@list_item, correction[:lock_version])

      unless @list_item.correct(name: correction[:name], category_id: correction[:category_id])
        return render :edit, status: :unprocessable_entity
      end
    end

    render_list
  rescue ListItem::StaleAction
    stale_list_action
  end

  def toggle
    current_household.with_list_lock do
      @list_item = current_household.list_items.on_list.find_by(id: params[:id])
      return missing_or_stale_item unless @list_item
      return stale_list_action if stale_version?(@list_item, params[:lock_version])

      return stale_list_action unless @list_item.toggle_purchased!
    end

    render_list
  rescue ListItem::StaleAction
    stale_list_action
  end

  # For items added by mistake: no confirmation. The toast in the response
  # (only for whoever removed it) carries a signed snapshot to undo with.
  def destroy
    current_household.with_list_lock do
      @list_item = current_household.list_items.on_list.find_by(id: params[:id])
      item = @list_item
      return missing_or_stale_item unless item
      return stale_list_action if stale_version?(item, params[:lock_version])

      item.destroy!
    end

    render_list toast: { message: "#{@list_item.name} removido",
      undo: { path: restore_list_items_path, params: { token: ListItemSnapshot.dump(@list_item) } } }
  end

  # "Desfazer" on the toast. The token must be a valid, unexpired snapshot of
  # this household's item; if the item is already back (or its name was added
  # again meanwhile) there is nothing to restore and the list is just resynced.
  def restore
    snapshot = ListItemSnapshot.load(params[:token])
    return head :not_found unless snapshot && snapshot["household_id"] == current_household.id

    current_household.restore_item(snapshot)

    render_list toast: :clear
  end

  private

  # Several names typed at once: the whole list, a summary in the form and a
  # highlight on every row that was added, revived or already there.
  def create_many(results)
    @groups = current_household.list_items_by_category
    @suggestions = BuyAgainSuggestions.call(current_household)
    @results = results.reject { |result| result.outcome == :invalid }
    @notice = helpers.add_items_notice(@results)

    respond_to do |format|
      format.turbo_stream { render :create_many }
      format.html { redirect_to root_path }
    end
  end

  # Whole list and summary, not one row: the counts and the "Compra feita"
  # button depend on every item. `toast` (locals for _toast, or :clear) also
  # fills or empties the toast, but only in this response: it is not broadcast.
  def render_list(toast: nil)
    respond_to do |format|
      format.turbo_stream do
        streams = [ list_refresh_stream ]
        streams << toast_stream(toast) if toast
        render turbo_stream: streams
      end
      format.html { redirect_to root_path }
    end
  end

  def toast_stream(toast)
    return turbo_stream.update("toast", "") if toast == :clear

    turbo_stream.update "toast", partial: "list_items/toast", locals: toast
  end

  def list_item_params
    params.expect(list_item: [ :name ])
  end

  def missing_or_stale_item
    return head :not_found if ListItem.where(id: params[:id]).exists? && !current_household.list_items.where(id: params[:id]).exists?

    stale_list_action
  end

  def missing_or_stale_frame
    return head :not_found if ListItem.where(id: params[:id]).exists? && !current_household.list_items.where(id: params[:id]).exists?

    return stale_list_action unless turbo_frame_request?

    render partial: "list_items/stale_frame", locals: { frame_id: request.headers["Turbo-Frame"] },
      status: :conflict
  end

  def stale_version?(item, version)
    version.present? && item.lock_version.to_s != version.to_s
  end
end
