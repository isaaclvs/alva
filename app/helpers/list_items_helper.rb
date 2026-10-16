module ListItemsHelper
  # "6 faltando · 2 no carrinho", dropping the parts that are zero.
  def list_summary_text(pending_count, purchased_count)
    parts = []
    parts << "#{pending_count} faltando" if pending_count.positive?
    parts << "#{purchased_count} no carrinho" if purchased_count.positive?
    parts.any? ? parts.join(" · ") : "Nada faltando"
  end
end
