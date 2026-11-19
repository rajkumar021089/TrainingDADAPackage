#!/usr/bin/env bash
# Run the filesystem/IS steps, then import and share projects inside Designer.
set -euo pipefail
shopt -s nullglob
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$PWD"
packages_dir='/c/SoftwareAG1015_LCL/IntegrationServer/instances/default/packages'
designer='/c/SoftwareAG1015_LCL/Designer/eclipse/eclipse.exe'
workspace=''
java_cmd=''
server_url='http://localhost:4455'
is_user='Administrator'
selected=''
order_file=''
timeout_seconds=900
apply=0
load_is=1
build_java=0

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
value() { [[ $# -ge 2 && -n "$2" ]] || die "Missing value for $1"; }
usage() {
  cat <<'HELP'
Usage: bash ImportAndMoveToIS.sh --workspace DIR [options]
  --workspace DIR     Existing Designer workspace (required; close Designer first)
  --designer PATH     eclipse.exe/eclipsec.exe or its directory
  --repo-root DIR     Parent of cloned repositories (default: current directory)
  --packages-dir DIR  Local Integration Server packages directory
  --package NAME      One inner NBK package; omit for all detected NBK packages
  --order FILE        Allow-list in dependency order, one package per line
  --java PATH         Designer-compatible JDK java.exe (not a JRE)
  --url URL           Local IS URL (default: http://localhost:4455)
  --user USER         IS user (default: Administrator; password prompted)
  --build-java        Also run jcode before loading packages
  --no-load           Link and import only; explicitly skip live IS loading
  --timeout SECONDS   Wait for Designer import (default: 900)
  --apply             Apply the plan; otherwise show a dry run
  --help

Requires the whole tools/move-to-is directory, Windows Git Bash, an existing
Designer workspace, Local Version Control Integration, EGit, and a matching JDK.
Designer must be closed. Source package folders need .project and .classpath.
The helper is compiled from the included source using the installed Eclipse jars.
No Git commit or push is performed by this launcher.
HELP
}
while (( $# )); do
  case "$1" in
    --workspace) value "$@"; workspace="$2"; shift 2 ;;
    --designer) value "$@"; designer="$2"; shift 2 ;;
    --repo-root) value "$@"; repo_root="$2"; shift 2 ;;
    --packages-dir) value "$@"; packages_dir="$2"; shift 2 ;;
    --package) value "$@"; selected="$2"; shift 2 ;;
    --order) value "$@"; order_file="$2"; shift 2 ;;
    --java) value "$@"; java_cmd="$2"; shift 2 ;;
    --url) value "$@"; server_url="$2"; shift 2 ;;
    --user) value "$@"; is_user="$2"; shift 2 ;;
    --timeout) value "$@"; timeout_seconds="$2"; shift 2 ;;
    --build-java) build_java=1; shift ;;
    --no-load) load_is=0; shift ;;
    --apply) apply=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done
[[ -n "$workspace" ]] || die '--workspace must identify your existing Designer workspace.'
[[ -d "$workspace" && -d "$workspace/.metadata" ]] || die "Not an existing Designer workspace: $workspace"
[[ -d "$repo_root" ]] || die "Repository parent does not exist: $repo_root"
[[ -d "$packages_dir" ]] || die "IS packages directory does not exist: $packages_dir"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] && (( timeout_seconds >= 30 && timeout_seconds <= 86400 )) || die '--timeout must be 30..86400 seconds.'
[[ -z "$selected" || -z "$order_file" ]] || die 'Use either --package or --order.'
[[ -f "$script_dir/MoveToIS.sh" && -f "$script_dir/importer/Bootstrap.java" && -f "$script_dir/importer/nbk/tools/lsdimport/Startup.java" ]] || die 'Keep this script with its MoveToIS.sh and importer source files.'
repo_root="$(cd "$repo_root" && pwd -P)"
workspace="$(cd "$workspace" && pwd -P)"
packages_dir="$(cd "$packages_dir" && pwd -P)"
if [[ -n "$order_file" ]]; then
  [[ -f "$order_file" ]] || die "Order file does not exist: $order_file"
  order_file="$(cd "$(dirname "$order_file")" && pwd -P)/$(basename "$order_file")"
fi
move_args=(--repo-root "$repo_root" --packages-dir "$packages_dir" --url "$server_url" --user "$is_user")
[[ -z "$selected" ]] || move_args+=(--package "$selected")
[[ -z "$order_file" ]] || move_args+=(--order "$order_file")
# The original helper supplies the same target collision and Git checks in both modes.
bash "$script_dir/MoveToIS.sh" "${move_args[@]}"

names=()
sources=()
git_dirs=()
for repo in "$repo_root"/*/; do
  for candidate in "$repo"NBK*/; do
    [[ -f "$candidate/manifest.v3" ]] || continue
    name="$(basename "$candidate")"
    [[ -z "$selected" || "$selected" == "$name" ]] || continue
    if [[ -n "$order_file" ]]; then
      included=0
      while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line%$'\r'}"; line="${line%%#*}"
        line="${line#"${line%%[![:space:]]*}"}"; line="${line%"${line##*[![:space:]]}"}"
        [[ "$line" != "$name" ]] || included=1
      done < "$order_file"
      (( included )) || continue
    fi
    [[ -f "$candidate/.project" && -f "$candidate/.classpath" ]] || die "Missing .project or .classpath in inner package: $candidate"
    names+=("$name")
    sources+=("$(cd "$candidate" && pwd -P)")
    git_dirs+=("$(git -C "$candidate" rev-parse --absolute-git-dir)")
  done
done
(( ${#names[@]} )) || die 'No package project was selected.'
printf '\nDESIGNER PLAN\n  Workspace: %s\n  Launcher:  %s\n  Projects:  %s\n' "$workspace" "$designer" "${#names[@]}"
printf '  Import source projects in place, configure installed LSD nature/builder,\n  and connect each project to its existing parent Git repository.\n'
if (( load_is )); then printf '  IS: create junctions, activate, enable and reload before starting Designer.\n'; else printf '  IS: create junctions only; loading was explicitly skipped.\n'; fi
if (( ! apply )); then printf '\nDRY RUN: no files changed. Close Designer and add --apply when ready.\n'; exit 0; fi
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) ;; *) die '--apply requires Windows Git Bash.' ;; esac
command -v cygpath >/dev/null || die 'cygpath is required.'
if [[ -d "$designer" ]]; then
  if [[ -f "$designer/eclipsec.exe" ]]; then designer="$designer/eclipsec.exe"; else designer="$designer/eclipse.exe"; fi
fi
[[ -f "$designer" ]] || die "Designer launcher does not exist: $designer"
designer_dir="$(cd "$(dirname "$designer")" && pwd -P)"
designer="$designer_dir/$(basename "$designer")"
[[ -d "$designer_dir/plugins" || -f "$designer_dir/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info" ]] || die 'The launcher directory is not an Eclipse/Designer installation.'

if [[ -z "$java_cmd" ]]; then
  # Prefer the runtime explicitly configured by this Designer installation.
  ini="$designer_dir/eclipse.ini"
  if [[ -f "$ini" ]]; then
    after_vm=0
    while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line%$'\r'}"
      [[ "$line" != '-vmargs' ]] || break
      if (( after_vm )); then
        candidate="$(cygpath -u "$line")"
        [[ "$candidate" == /* ]] || candidate="$designer_dir/$candidate"
        [[ ! -d "$candidate" ]] || candidate="$candidate/java.exe"
        [[ "${candidate,,}" != *javaw.exe ]] || candidate="${candidate%/*}/java.exe"
        [[ ! -f "$candidate" ]] || java_cmd="$candidate"
        break
      fi
      [[ "$line" != '-vm' ]] || after_vm=1
    done < "$ini"
  fi
fi
if [[ -z "$java_cmd" ]]; then
  for candidate in "$designer_dir/../../jvm/jvm/bin/java.exe" "${JAVA_HOME:-/nonexistent}/bin/java.exe"; do
    if [[ -f "$candidate" ]]; then java_cmd="$candidate"; break; fi
  done
fi
[[ -n "$java_cmd" && -f "$java_cmd" ]] || die 'Supply --java with the matching JDK java.exe used for Designer.'
java_cmd="$(cygpath -u "$java_cmd")"
run_dir="$(mktemp -d "$repo_root/DesignerImport-logs-XXXXXXXX")"
umask 077
win() { cygpath -am "$1"; }
plan="$run_dir/import.properties"
result="$run_dir/result.properties"
{
  printf 'format=1\nworkspace=%s\nresult=%s\ncount=%s\n' "$(win "$workspace")" "$(win "$result")" "${#names[@]}"
  for i in "${!names[@]}"; do
    for path in "${sources[i]}" "${git_dirs[i]}"; do [[ "$path" != *$'\n'* && "$path" != *$'\r'* ]] || die 'Newlines in paths are unsupported.'; done
    printf 'package.%s.name=%s\npackage.%s.path=%s\npackage.%s.gitdir=%s\n' "$i" "${names[i]}" "$i" "$(win "${sources[i]}")" "$i" "$(win "${git_dirs[i]}")"
  done
} > "$plan"
printf '\nRun logs: %s\nChecking the workspace lock and compiling the import helper...\n' "$run_dir"
MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$java_cmd" "$(win "$script_dir/importer/Bootstrap.java")" \
  "$(win "$designer_dir")" "$(win "$script_dir/importer")" "$(win "$run_dir")" "$(win "$workspace")" > "$run_dir/bootstrap.log" 2>&1 || { cat "$run_dir/bootstrap.log" >&2; die 'Helper build/setup failed before IS changes.'; }

apply_args=("${move_args[@]}" --apply)
(( ! load_is )) || apply_args+=(--online)
(( ! build_java )) || apply_args+=(--build-java)
(cd "$run_dir" && bash "$script_dir/MoveToIS.sh" "${apply_args[@]}") || die 'The IS phase failed. Designer was not started. Keep the run logs; completed junctions were preserved.'
printf '\nStarting Designer and importing %s project(s)...\n' "${#names[@]}"
MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$designer" -clean -data "$(win "$workspace")" -vm "$(win "$java_cmd")" \
  --launcher.appendVmargs -vmargs "-Dnbk.lsdimport.plan=$(win "$plan")" > "$run_dir/designer-launch.log" 2>&1 &
deadline=$((SECONDS + timeout_seconds))
while [[ ! -f "$result" ]]; do
  if (( SECONDS >= deadline )); then
    die "Timed out waiting for Designer. Check $run_dir and workspace .metadata/.log. Designer was left open; do not rerun until it is closed."
  fi
  # A GUI launcher may exit after delegating to its JVM, so its PID is not an outcome signal.
  sleep 1
done
if ! grep -q '^status=SUCCESS' "$result"; then
  cat "$result" >&2
  [[ ! -f "$run_dir/import.log" ]] || cat "$run_dir/import.log" >&2
  die "Designer import did not finish successfully. See $run_dir. Imported projects and links were preserved."
fi
cat "$result"
printf '\nDesigner imported and Git-connected the selected projects.\n'
if (( load_is )); then
  printf 'Review the IS response logs and the package status in Designer.\n'
else
  printf 'Live IS loading was skipped (--no-load). Start/reload IS separately.\n'
fi
printf 'Before committing, review .project changes and save one service to verify Git Staging.\nRun logs: %s\n' "$run_dir"
