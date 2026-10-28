#!/usr/bin/env bash
# Runs the farm game test on each build of the test library (testdata/library), for the official and the Mythian
# edition, and writes one result line per build and edition to build/library-results.txt.
#   tools/run_library.sh          all builds
#   tools/run_library.sh 03 06    only these (by number)
# Needs xvfb-run (Linux) and the JDK in JAVA_HOME. A build takes 1 to 10 minutes.
set -u
cd "$(dirname "$0")/.."
results=build/library-results.txt
mkdir -p build
: > "$results"
pick=("$@")
for file in testdata/library/*.litematic; do
  name=$(basename "$file" .litematic)
  num=${name%%-*}
  if [ ${#pick[@]} -gt 0 ] && [[ ! " ${pick[*]} " =~ " $num " ]]; then
    continue
  fi
  for edition in official mythian; do
    log=build/library-$name-$edition.log
    echo "== $name ($edition)"
    PRINTER_ADDON_ONLY=FarmTest PRINTER_ADDON_SCHEMATIC="$PWD/$file" PRINTER_ADDON_PREBUILT_LAYERS=0 \
      xvfb-run -a ./gradlew runClientGameTest --offline -Pedition=$edition > "$log" 2>&1
    status=$?
    summary=$(grep -E "FARM TEST|blocks/min|wrong|FAILED|PASSED" "$log" | tail -3 | tr '\n' ' ')
    echo "$name $edition exit=$status $summary" | tee -a "$results"
  done
done
echo "Results: $results"
