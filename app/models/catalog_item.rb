class CatalogItem < ApplicationRecord
  belongs_to :category

  before_validation :normalize_name

  validates :name, presence: true
  validates :normalized_name, uniqueness: true

  private

  def normalize_name
    self.normalized_name = TextNormalizer.call(name)
  end
end
