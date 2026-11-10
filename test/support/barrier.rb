require "timeout"

# Rendezvous point so two threads reach the same operation together, used by
# the tests that run on independent SQLite connections.
class Barrier
  def initialize(parties)
    @parties = parties
    @arrived = 0
    @mutex = Mutex.new
    @condition = ConditionVariable.new
  end

  def wait
    @mutex.synchronize do
      @arrived += 1
      if @arrived == @parties
        @condition.broadcast
      else
        Timeout.timeout(5) do
          @condition.wait(@mutex) while @arrived < @parties
        end
      end
    end
  end
end
