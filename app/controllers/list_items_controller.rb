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
    result = current_household.add_item(list_item_params[:name])

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

  def toggle
    @list_item = current_household.list_items.on_list.find(params[:id])
    @list_item.toggle_purchased!

    render_list
  end

  # For items added by mistake: no confirmation, re-adding is one tap away.
  def destroy
    current_household.list_items.on_list.find(params[:id]).destroy!

    render_list
  end

  private

  # Whole list and summary, not one row: the counts and the "Compra feita"
  # button depend on every item.
  def render_list
    respond_to do |format|
      format.turbo_stream do
        render partial: "refresh", locals: {
          groups: current_household.list_items_by_category,
          suggestions: BuyAgainSuggestions.call(current_household)
        }
      end
      format.html { redirect_to root_path }
    end
  end

  def list_item_params
    params.expect(list_item: [ :name ])
  end
end
