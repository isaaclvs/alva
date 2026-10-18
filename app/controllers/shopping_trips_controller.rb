class ShoppingTripsController < ApplicationController
  # "Compra feita": archives the purchased items, pending ones carry over.
  # The household broadcasts to every client; the response also renders the list
  # so this client is right even without the cable.
  def create
    current_household.finish_shopping!

    respond_to do |format|
      format.turbo_stream do
        render partial: "list_items/refresh", locals: { groups: current_household.list_items_by_category, suggestions: BuyAgainSuggestions.call(current_household) }
      end
      format.html { redirect_to root_path }
    end
  end
end
