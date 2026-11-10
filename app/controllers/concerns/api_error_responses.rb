# JSON error shape shared by the public and protected API controllers.
module ApiErrorResponses
  private

  def render_api_error(error, status)
    render json: { error: }, status: status
  end
end
