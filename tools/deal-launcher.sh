#!/bin/sh
DEAL_HOME=$(cd "$(dirname "$0")/.." && pwd)
exec java -cp "$DEAL_HOME" deal.Main "$@"
