#!/usr/bin/env bash
# Writes the SMD Artifact of the Spring Boot application in the current directory to $CHANGEVIZ_OUT (or --out).
#
#   extract-smd.sh --main-class <class> [--source-set <name>] [--project <path>] [--app <name>]
#                  [--config-class <class>]... [--property <key=value>]... [--gradle-arg <arg>]... [--out <file>]
#
# It runs the application's own Gradle wrapper with an init script, so the application needs no dependency or class.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
main_class=""
source_set="test"
project=":"
app=""
out="${CHANGEVIZ_OUT:-}"
properties=""
config_classes=""
gradle_args=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --main-class) main_class="$2"; shift 2 ;;
        --source-set) source_set="$2"; shift 2 ;;
        --project) project="$2"; shift 2 ;;
        --app) app="$2"; shift 2 ;;
        --property) properties+="$2"$'\n'; shift 2 ;;
        --config-class) config_classes+="$2"$'\n'; shift 2 ;;
        --gradle-arg) gradle_args+=("$2"); shift 2 ;;
        --out) out="$2"; shift 2 ;;
        *) echo "extract-smd.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

if [[ -z "$main_class" ]]; then
    echo "extract-smd.sh: --main-class is required" >&2
    exit 2
fi
if [[ -z "$out" ]]; then
    echo "extract-smd.sh: set CHANGEVIZ_OUT or pass --out" >&2
    exit 2
fi
if [[ ! -x ./gradlew ]]; then
    echo "extract-smd.sh: no ./gradlew in $(pwd); run it at the root of the application's checkout" >&2
    exit 2
fi
out="$(cd "$(dirname "$out")" && pwd)/$(basename "$out")"

task="exportSmdArtifact"
[[ "$project" != ":" ]] && task="$project:exportSmdArtifact"

args=(
    --init-script "$here/smd-artifact-export.init.gradle"
    --no-configuration-cache
    --console=plain
    -PsmdExport.sources="$here/src"
    -PsmdExport.mainClass="$main_class"
    -PsmdExport.out="$out"
    -PsmdExport.project="$project"
    -PsmdExport.sourceSet="$source_set"
    -PsmdExport.properties="$properties"
    -PsmdExport.configClasses="$config_classes"
)
[[ -n "$app" ]] && args+=(-PsmdExport.app="$app")
args+=("${gradle_args[@]}")

# All of Gradle's and the application's output goes to stderr; the Artifact goes only to the file.
./gradlew "${args[@]}" "$task" >&2
