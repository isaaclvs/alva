class ApplicationController < ActionController::Base
  # Only allow modern browsers supporting webp images, web push, badges, import maps, CSS nesting, and CSS :has.
  allow_browser versions: :modern

  # Changes to the importmap will invalidate the etag for HTML responses
  stale_when_importmap_changes

  before_action :prevent_private_caching
  before_action :require_household_access

  helper_method :current_household

  private

  def require_household_access
    return if HouseholdAccess.authorized?(cookies.encrypted[HouseholdAccess::COOKIE])

    redirect_to login_path, status: :see_other
  end

  def prevent_private_caching
    response.headers["Cache-Control"] = "no-store" if HouseholdAccess.enabled?
  end

  # Single shared household until multi-tenancy exists (see AGENTS.md).
  def current_household
    @current_household ||= Household.first!
  end

  # Same stream shape as the household broadcast: one category for purchases,
  # the complete snapshot for structural changes and recovery.
  def list_refresh_stream(category: :all)
    if category != :all
      return render_to_string partial: "list_items/purchase_refresh", locals: {
        groups: current_household.list_items_by_category, category: category
      }
    end

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
