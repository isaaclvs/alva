class SessionsController < ApplicationController
  # A bounded, process-local store also works without a configured cache.
  LOGIN_RATE_LIMIT_STORE = ActiveSupport::Cache::MemoryStore.new

  skip_before_action :require_household_access
  rate_limit to: 10, within: 3.minutes, only: :create, store: LOGIN_RATE_LIMIT_STORE,
    with: -> { render_login_error("Muitas tentativas. Aguarde alguns minutos e tente de novo.", :too_many_requests) }

  def new
    redirect_to root_path unless HouseholdAccess.enabled?
  end

  def create
    return redirect_to root_path unless HouseholdAccess.enabled?

    if HouseholdAccess.authenticate(params[:password])
      reset_session
      cookies.encrypted[HouseholdAccess::COOKIE] = {
        value: HouseholdAccess.token, expires: HouseholdAccess::DURATION.from_now,
        httponly: true, same_site: :lax, secure: request.ssl?
      }
      redirect_to root_path, status: :see_other
    else
      render_login_error("Senha incorreta. Tente novamente.", :unprocessable_entity)
    end
  end

  def destroy
    cookies.delete(HouseholdAccess::COOKIE)
    reset_session
    redirect_to login_path, status: :see_other
  end

  private

  def render_login_error(message, status)
    @error = message
    render :new, status: status
  end
end
