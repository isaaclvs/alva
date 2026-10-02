class ListItemsController < ApplicationController
  def index
    @household = current_household
    @list_item = ListItem.new
    @groups = @household.list_items_by_category
  end

  def create
    @list_item = current_household.list_items.new(list_item_params)

    if @list_item.save
      @groups = current_household.list_items_by_category
      respond_to do |format|
        format.turbo_stream
        format.html { redirect_to root_path }
      end
    elsif @list_item.errors.of_kind?(:name, :duplicate_pending)
      existing = current_household.pending_item_named(@list_item.name)
      render turbo_stream: [
        turbo_stream.replace("list_item_form", partial: "form",
          locals: { list_item: ListItem.new, notice: "#{existing.name} já está na lista" }),
        turbo_stream.replace(existing, partial: "list_item", locals: { list_item: existing, highlight: true })
      ]
    else
      render turbo_stream: turbo_stream.replace("list_item_form", partial: "form", locals: { list_item: @list_item }),
             status: :unprocessable_entity
    end
  end

  def toggle
    @list_item = current_household.list_items.find(params[:id])
    @list_item.toggle_purchased!

    respond_to do |format|
      format.turbo_stream { render turbo_stream: turbo_stream.replace(@list_item) }
      format.html { redirect_to root_path }
    end
  end

  private

  def list_item_params
    params.expect(list_item: [ :name ])
  end
end
