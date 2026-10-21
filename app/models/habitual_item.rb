class HabitualItem < ApplicationRecord
  belongs_to :household

  validates :name, presence: true
  validates :name, length: { maximum: 100 }
  validates :normalized_name, uniqueness: { scope: :household_id }

  before_validation :normalize_name

  private

  def normalize_name
    self.name = name.to_s.squish
    self.normalized_name = TextNormalizer.call(name)
  end
end
