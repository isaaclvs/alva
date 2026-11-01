module ApplicationCable
  class Connection < ActionCable::Connection::Base
    def connect
      reject_unauthorized_connection unless HouseholdAccess.authorized?(cookies.encrypted[HouseholdAccess::COOKIE])
    end
  end
end
