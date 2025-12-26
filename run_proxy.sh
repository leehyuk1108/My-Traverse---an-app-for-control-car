#!/bin/bash
# run_proxy.sh

MITM_BIN=~/Library/Python/3.9/bin/mitmdump

if [ ! -f "$MITM_BIN" ]; then
    echo "mitmdump not found at $MITM_BIN"
    exit 1
fi

echo "Starting Proxy Server (mitmdump) on port 8080..."
$MITM_BIN -s proxy_addon.py -p 8080 --set block_global=false
