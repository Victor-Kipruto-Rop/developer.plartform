#!/bin/sh
set -eu

case "${DATABASE_URL:-}" in
  postgres://*)
    DATABASE_URL="jdbc:postgresql://${DATABASE_URL#postgres://}"
    export DATABASE_URL
    ;;
  postgresql://*)
    DATABASE_URL="jdbc:${DATABASE_URL}"
    export DATABASE_URL
    ;;
esac

exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar
