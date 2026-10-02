# Idempotent: safe to run multiple times (bin/rails db:seed).

Household.find_or_create_by!(name: "Casa")
