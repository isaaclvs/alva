# The read-only "Compras" screen: what "Compra feita" archived, one month per
# page (see ShoppingHistory). Nothing here edits history; the one action,
# "Adicionar tudo de novo", puts a trip's names back on the live list through
# the same Household#add_item_names as any other add, so duplicates, purchases
# brought back and the single broadcast behave exactly like a typed add.
class ShoppingHistoryController < ApplicationController
  # A month the person cannot ask for (an old link) falls back to the newest
  # one instead of an error: history is a place to look, not a resource.
  def index
    @page = ShoppingHistory.call(current_household, month: params[:month])
  end

  # "Adicionar tudo de novo": the names of the trip, in the order the screen
  # shows them. The answer carries the add feedback the add screen uses, and
  # only to this device; the list itself reaches every device through the
  # broadcast Household#add_item_names already publishes.
  def add_all
    entry = ShoppingHistory.trip(current_household, params[:id])
    return redirect_to shopping_history_path, status: :see_other unless entry

    results = current_household.add_item_names(entry.names).reject { |result| result.outcome == :invalid }
    message = helpers.add_items_notice(results)
    tone = results.any? { |result| result.outcome.in?(%i[created revived]) } ? :added : :already

    respond_to do |format|
      format.turbo_stream do
        render turbo_stream: turbo_stream.update("history_feedback", partial: "list_items/add_feedback",
          locals: { message:, tone: })
      end
      format.html { redirect_to shopping_history_path, status: :see_other, notice: message }
    end
  end
end
