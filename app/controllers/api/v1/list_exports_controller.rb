module Api
  module V1
    # The same PDF as the PWA's /lista.pdf (pending items by category), for the
    # native app: the household comes from the sync credential (BaseController)
    # instead of the cookie. Failures are the JSON errors of the rest of the API.
    class ListExportsController < BaseController
      include ListPdfDownload

      def show
        send_list_pdf(current_household)
      end
    end
  end
end
