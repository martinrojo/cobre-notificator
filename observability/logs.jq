# Renders the apps' ECS JSON logs as one readable line per event:
#   docker compose logs -f --no-log-prefix notificator | jq -Rr -f observability/logs.jq
# Event fields (event_id, client_id, ...) go as key=value; an exception goes last in
# `exception` with its line breaks escaped, so it never spans several lines. ECS puts an
# exception in the `error` object; a plain `error` key-value (a string) is just another field.
def fields:
  to_entries
  | map(select(.key | IN("@timestamp", "log", "process", "service", "message", "ecs") | not))
  | map(select(.key != "error" or (.value | type) != "object"))
  | map("\(.key)=\(.value | tostring)")
  | join(" ");

(fromjson? // null) as $e
| if $e == null then .
  else
    [ $e["@timestamp"], $e.service.name, $e.log.level, ($e.log.logger | split(".") | last),
      $e.message, ($e | fields),
      (if ($e.error | type) == "object" then "exception=\($e.error.stack_trace // "\($e.error.type): \($e.error.message)")" else empty end)
    ]
    | map(select(. != "")) | join(" ")
  end
| gsub("\r?\n"; "\\n") | gsub("\t"; "\\t")
