class QuickAddController < ActionController::API
  before_action :authenticate_device!

  def create
    names = params[:name].to_s
    return render json: { error: "invalid_name" }, status: :unprocessable_entity if ItemNameSplitter.call(names).empty?

    results = @device.household.add_items(names)
    render json: { results: results.map { |result| { name: result.item.name, outcome: result.outcome } } }
  end

  private

  def authenticate_device!
    @device = AndroidDevice.authenticate(request.authorization.to_s.delete_prefix("Bearer "))
    render json: { error: "unauthorized" }, status: :unauthorized unless @device
  end
end
