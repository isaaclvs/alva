Rails.application.routes.draw do
  # Define your application routes per the DSL in https://guides.rubyonrails.org/routing.html

  # Reveal health status on /up that returns 200 if the app boots with no exceptions, otherwise 500.
  # Can be used by load balancers and uptime monitors to verify that the app is live.
  get "up" => "rails/health#show", as: :rails_health_check

  # Render dynamic PWA files from app/views/pwa/* (remember to link manifest in application.html.erb)
  get "manifest" => "rails/pwa#manifest", as: :pwa_manifest
  get "service-worker" => "rails/pwa#service_worker", as: :pwa_service_worker

  # Defines the root path route ("/")
  resources :list_items, only: [ :create, :destroy ] do
    patch :toggle, on: :member
  end
  resources :shopping_trips, only: :create

  # GET /lista.pdf: shopping list download for printing.
  get "lista.pdf", to: "list_exports#show", as: :list_export, format: false

  root "list_items#index"
end
