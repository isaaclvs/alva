module Api
  module V1
    # The household's whole synchronized state in one consistent read. The
    # household comes from the credential (see BaseController); nothing in the
    # request can pick another one.
    class SnapshotsController < BaseController
      def show
        render json: SyncSnapshot.call(current_household)
      end
    end
  end
end
