#!/usr/bin/env bash

# The idea here is to start a child (sleep 10) that will live on after we've
# sigkilled ourselves -- which happens when we trap a sigterm

pid=$$
trap "kill -9 $pid" TERM
sleep 10 &
printf ready

while true; do
    sleep 1
done
