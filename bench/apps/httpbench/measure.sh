#!/bin/sh
# The httpbench measurement protocol (bench/apps/httpbench/README.md has
# the numbers this produced). Run from the app directory with the built
# binary in place (cohost -T:build build -> target/httpbench/run):
#   sh measure.sh
# Needs: wrk and nginx on PATH for the two reference rows, curl for the
# warmups. Server and bench are separate processes; every bench row is
# 100,000 requests, 3 runs, and this script prints all three so the
# median can be taken by eye.
set -u
BIN=${BIN:-target/httpbench/run}
PORT=${PORT:-8071}
ulimit -n 200000 2>/dev/null || true
echo "box: $(uname -srm)  load: $(cat /proc/loadavg 2>/dev/null || uptime)"
echo "ephemeral port range: $(cat /proc/sys/net/ipv4/ip_local_port_range 2>/dev/null || sysctl -n net.inet.ip.portrange.first net.inet.ip.portrange.last 2>/dev/null)"
echo "binary: $BIN"

"$BIN" serve --port $PORT > /tmp/httpbench-serve.log 2>&1 &
SERVER=$!
sleep 2
curl -s "http://127.0.0.1:$PORT/" > /dev/null || { echo "server did not answer"; cat /tmp/httpbench-serve.log; kill $SERVER; exit 1; }

for C in 100 1000 10000 ${CAP:-100000}; do
  for R in 1 2 3; do
    echo "== httpbench bench c=$C run $R"
    "$BIN" bench --url "http://127.0.0.1:$PORT/" --requests 100000 --concurrency $C
  done
done

echo "== wrk reference against the httpbench server (c=1000)"
wrk -t4 -c1000 -d10s "http://127.0.0.1:$PORT/"

kill $SERVER 2>/dev/null
sleep 1

echo "== httpbench bench against nginx (reference server)"
mkdir -p /tmp/httpbench-nginx
cat > /tmp/httpbench-nginx/nginx.conf <<EOF
pid /tmp/httpbench-nginx/nginx.pid;
error_log /tmp/httpbench-nginx/error.log;
worker_processes 4;
events { worker_connections 4096; }
http { access_log off;
  server { listen 127.0.0.1:$PORT;
    location / { return 200 '{"service":"httpbench","ok":true,"items":[0,7,14,21,28,35,42,49,56,63,70,77,84,91,98,5,12,19,26,33,40,47,54,61,68,75,82,89,96,3,10,17,24,31,38,45,52,59,66,73,80,87,94,1,8,15,22,29],"pad":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}'; } } }
EOF
nginx -c /tmp/httpbench-nginx/nginx.conf -p /tmp/httpbench-nginx -t || exit 1
nginx -c /tmp/httpbench-nginx/nginx.conf -p /tmp/httpbench-nginx
sleep 1
for R in 1 2 3; do
  echo "== httpbench bench c=1000 run $R (nginx)"
  "$BIN" bench --url "http://127.0.0.1:$PORT/" --requests 100000 --concurrency 1000
done
nginx -c /tmp/httpbench-nginx/nginx.conf -p /tmp/httpbench-nginx -s quit 2>/dev/null
