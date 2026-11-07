#!/bin/bash
# Агент: Claude кладёт задания в ~/Documents/Claude/agent/in/*.sh, агент выполняет их на Маке
# и пишет вывод в agent/out/<имя>.txt. Так Claude сам работает с телевизором. Остановить: Ctrl+C.
DIR="$HOME/Documents/Claude"
Q="$DIR/agent"
mkdir -p "$Q/in" "$Q/out" "$Q/done"
# Двое агентов на одну очередь не нужны: второй запуск (например, при входе в систему) тихо выходит.
if [ -f "$Q/pid" ] && kill -0 "$(cat "$Q/pid")" 2>/dev/null; then echo "Агент уже работает"; exit 0; fi
echo $$ > "$Q/pid"
# Пока Мак на зарядке, он не засыпает, и Claude не теряет телевизор (на батарее Мак спит как обычно).
caffeinate -s -w $$ &
echo "Агент запущен $(date '+%H:%M'). Окно не закрывай; остановить — Ctrl+C."
date '+%s' > "$Q/alive"
while true; do
  for f in "$Q/in"/*.sh; do
    [ -f "$f" ] || continue
    n=$(basename "$f" .sh)
    mv "$f" "$Q/done/$n.sh"
    echo "$(date '+%H:%M:%S') ▶ $n"
    ( cd "$DIR" && bash "$Q/done/$n.sh" < /dev/null ) > "$Q/out/$n.part" 2>&1
    echo "== exit $?" >> "$Q/out/$n.part"
    mv "$Q/out/$n.part" "$Q/out/$n.txt"
    echo "$(date '+%H:%M:%S') ✓ $n"
  done
  date '+%s' > "$Q/alive"
  sleep 2
done
