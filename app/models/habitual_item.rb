class HabitualItem < ApplicationRecord
  include HasPublicId

  MAX_NAME_LENGTH = 100

  belongs_to :household

  validates :name, presence: true
  validates :name, length: { maximum: MAX_NAME_LENGTH }
  validates :normalized_name, uniqueness: { scope: :household_id }

  before_validation :normalize_name

  private

  def normalize_name
    self.name = name.to_s.squish
    self.normalized_name = TextNormalizer.call(name)
  end
end
