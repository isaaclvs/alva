# Lets system tests exercise a real HTTP 500 response from the Rails server.
# The opt-in header is added only by the failure regression tests.
class InjectedServerError
  def initialize(app)
    @app = app
  end

  def call(env)
    return @app.call(env) unless env["HTTP_X_ALVA_TEST_SERVER_ERROR"] == "1"

    [ 500, { "content-type" => "text/vnd.turbo-stream.html; charset=utf-8" }, [ "" ] ]
  end
end
