#!/usr/bin/env bash

logger my pid is $$

trap 'logger killing $$; kill -9 $$' SIGTERM

1>&2 sleep 8 &
wait
