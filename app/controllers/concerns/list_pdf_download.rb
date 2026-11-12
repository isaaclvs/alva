# The shopping-list PDF as a download, shared by the PWA (/lista.pdf, cookie)
# and the API v1 (/api/v1/list.pdf, sync credential) so both hand out the same
# bytes under the same name. Who may ask is each controller's business.
module ListPdfDownload
  private

  def send_list_pdf(household)
    send_data ListPdf.call(household, now: Time.current),
      type: "application/pdf",
      disposition: "attachment",
      filename: "lista-#{Date.current.iso8601}.pdf"
  end
end
