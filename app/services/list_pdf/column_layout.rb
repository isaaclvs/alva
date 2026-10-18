class ListPdf
  # Places the category cards of the PDF into two columns per page, in reading
  # order: down the left column, then the right one, then the next page.
  #
  # A list that fits on the first page is balanced between its two columns.
  # A longer one fills column after column; a card that doesn't fit is split
  # and the rest continues at the top of the next column (marked continued).
  #
  # Returns pages, each [left_pieces, right_pieces].
  class ColumnLayout
    # items: the rows in this piece; total: how many the whole category has.
    Piece = Struct.new(:label, :items, :total, :continued, keyword_init: true)

    # Don't leave a heading at the bottom of a column with a single item under
    # it when more items follow.
    MIN_ROWS = 2

    def self.call(groups, **options)
      new(**options).call(groups)
    end

    # height_for: card height for a number of items. first_capacity/capacity:
    # usable column height on the first page (below the header) and the others.
    def initialize(height_for:, first_capacity:, capacity:, gap:)
      @height_for = height_for
      @first_capacity = first_capacity
      @capacity = capacity
      @gap = gap
    end

    def call(groups)
      balanced(groups) || sequential(groups)
    end

    private

    def balanced(groups)
      heights = groups.map { |_, items| @height_for.call(items.size) }

      split = (0..groups.size)
        .map { |at| [ at, stack_height(heights.first(at)), stack_height(heights.drop(at)) ] }
        .select { |_, left, right| left <= @first_capacity && right <= @first_capacity }
        .min_by { |at, left, right| [ [ left, right ].max, -at ] }
      return unless split

      at = split.first
      [ [ pieces(groups.first(at)), pieces(groups.drop(at)) ] ]
    end

    def sequential(groups)
      @pages = [ [ [], [] ] ]
      @column = 0
      @used = 0

      groups.each do |label, items|
        remaining = items
        continued = false

        until remaining.empty?
          rows = rows_that_fit(remaining.size)

          if rows.zero?
            next_column
            next
          end

          place(Piece.new(label: label, items: remaining.first(rows), total: items.size, continued: continued), rows)
          remaining = remaining.drop(rows)
          continued = true
          next_column if remaining.any?
        end
      end

      @pages
    end

    def rows_that_fit(wanted)
      room = capacity - @used - (@used.zero? ? 0 : @gap)
      rows = (1..wanted).select { |count| @height_for.call(count) <= room }.max.to_i
      # An empty column always takes something, so a huge card can't loop forever.
      return [ rows, 1 ].max if @used.zero?

      rows >= [ MIN_ROWS, wanted ].min ? rows : 0
    end

    def place(piece, rows)
      @pages.last[@column] << piece
      @used += (@used.zero? ? 0 : @gap) + @height_for.call(rows)
    end

    def next_column
      if @column.zero?
        @column = 1
      else
        @pages << [ [], [] ]
        @column = 0
      end
      @used = 0
    end

    def capacity
      @pages.size == 1 ? @first_capacity : @capacity
    end

    def stack_height(heights)
      heights.sum + @gap * [ heights.size - 1, 0 ].max
    end

    def pieces(groups)
      groups.map { |label, items| Piece.new(label: label, items: items, total: items.size, continued: false) }
    end
  end
end
