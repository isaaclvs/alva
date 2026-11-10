# A stable UUID for the records the sync protocol exchanges (see
# docs/plans/android-sync-contract-v1.md). The integer primary key stays
# internal; public_id is what the Android app stores and sends back.
#
# The client can generate the UUID before the record exists, so a value already
# set is never overwritten. Uniqueness is enforced by a database index instead
# of a validation, which would add a query to every insert.
module HasPublicId
  extend ActiveSupport::Concern

  included do
    before_validation :assign_public_id, on: :create
    validates :public_id, presence: true
  end

  private

  def assign_public_id
    self.public_id = SecureRandom.uuid if public_id.blank?
  end
end
