class ApplicationController < ActionController::Base
  # Only allow modern browsers supporting webp images, web push, badges, import maps, CSS nesting, and CSS :has.
  allow_browser versions: :modern

  # Changes to the importmap will invalidate the etag for HTML responses
  stale_when_importmap_changes

  helper_method :current_household

  private

  # Single shared household until multi-tenancy exists (see AGENTS.md).
  def current_household
    @current_household ||= Household.first!
  end

  # The whole list, its summary and the "buy again" chips as Turbo Streams: the
  # same partial Household#broadcast_list sends, for this client's response.
  def list_refresh_stream
    render_to_string partial: "list_items/refresh", locals: {
      groups: current_household.list_items_by_category,
      suggestions: BuyAgainSuggestions.call(current_household),
      household: current_household
    }
  end

  def stale_list_action
    notice = "A lista mudou em outro aparelho. Confira a versão atualizada."
    respond_to do |format|
      format.turbo_stream do
        render turbo_stream: [
          list_refresh_stream,
          turbo_stream.update("toast", partial: "list_items/stale_notice", locals: { message: notice })
        ], status: :conflict
      end
      format.html { redirect_to root_path, alert: notice, status: :see_other }
    end
  end
end
