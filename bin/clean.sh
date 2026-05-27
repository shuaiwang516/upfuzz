#!/bin/bash

# This scripts stop the fuzzing server, client and remove the containers created
# Usage: ./clean.sh [--force]
#   --force: Skip confirmation and remove containers directly

# Check if --force flag is provided
FORCE=false
if [[ "$1" == "--force" ]]; then
    FORCE=true
fi

echo "Killing upfuzz processes..."
pgrep -u $(id -u) -f '.*config\.json$' | xargs -r kill -9
pgrep --euid $USER qemu | xargs -r kill -9 # kill all lurking qemu instances

echo "Removing upfuzz containers..."
# Only remove containers created by upfuzz project
# Look for containers that have upfuzz_ in either the name or image
UPFUZZ_CONTAINERS=$(docker ps -a --format "table {{.Names}}\t{{.Image}}" | grep "upfuzz_" | awk '{print $1}')

if [ -n "$UPFUZZ_CONTAINERS" ]; then
    echo "Found upfuzz containers:"
    echo "$UPFUZZ_CONTAINERS"
    echo ""
    
    if [ "$FORCE" = true ]; then
        echo "Force mode: removing containers without confirmation..."
        while IFS= read -r container; do
            [ -n "$container" ] || continue
            if ! timeout 20s docker rm -f "$container"; then
                echo "Warning: timed out removing container $container"
            fi
        done <<< "$UPFUZZ_CONTAINERS"
    else
        read -p "Do you want to remove these containers? (y/N): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            echo "Removing these containers..."
            while IFS= read -r container; do
                [ -n "$container" ] || continue
                if ! timeout 20s docker rm -f "$container"; then
                    echo "Warning: timed out removing container $container"
                fi
            done <<< "$UPFUZZ_CONTAINERS"
        else
            echo "Skipping container removal."
        fi
    fi
else
    echo "No upfuzz containers found."
fi

echo "Cleaning up unused Docker resources..."

timeout 20s docker network prune -f || echo "Warning: docker network prune timed out"
timeout 20s docker container prune -f || echo "Warning: docker container prune timed out"

echo "Cleanup completed!"
