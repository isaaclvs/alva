# Moves items already on the list out of "Outros" once the dictionary knows
# where they belong (see DictionaryRecategorizer). db:seed only grows the
# dictionary; this is the one-off pass for lists that were written before it.
# Idempotent: a second run moves nothing and touches no revision.
namespace :dictionary do
  desc "Recategorize live items the dictionary now knows, leaving hand-picked categories alone"
  task recategorize: :environment do
    total = DictionaryRecategorizer.call_all
    puts "#{total} #{total == 1 ? 'item recategorized' : 'items recategorized'}"
  end
end
