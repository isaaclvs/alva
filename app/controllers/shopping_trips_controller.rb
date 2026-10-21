class ShoppingTripsController < ApplicationController
  # "Compra feita": archives the purchased items, pending ones carry over.
  # The household broadcasts to every client; the response also renders the list
  # so this client is right even without the cable, plus a toast (only here, not
  # in the broadcast) to undo a mis-tap.
  def create
    trip = current_household.finish_shopping!

    respond_to do |format|
      format.turbo_stream do
        streams = [ list_refresh_stream ]
        streams << turbo_stream.update("toast", partial: "list_items/toast",
          locals: { message: "Compra registrada", undo: { path: shopping_trip_path(trip), method: :delete } }) if trip
        render turbo_stream: streams
      end
      format.html { redirect_to root_path }
    end
  end

  # "Desfazer" on the toast: the items come back to the list and the trip goes
  # away. 404 for another household; stale trips return a conflict and a list
  # refresh so the client can continue from the current state.
  def destroy
    trip = current_household.shopping_trips.find_by(id: params[:id])
    return head :not_found if !trip && ShoppingTrip.exists?(params[:id])
    return stale_list_action unless trip
    return stale_list_action unless current_household.undo_shopping!(trip)

    respond_to do |format|
      format.turbo_stream do
        render turbo_stream: [
          list_refresh_stream,
          turbo_stream.update("toast", "")
        ]
      end
      format.html { redirect_to root_path }
    end
  end
end
