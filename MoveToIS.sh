#!/usr/bin/env bash
# Windows Git Bash. Default: show the plan without changing anything.
set -euo pipefail
shopt -s nullglob

repo_root="$PWD"
packages_dir='/c/SoftwareAG1015_LCL/IntegrationServer/instances/default/packages'
server_url='http://localhost:4455'
is_user='Administrator'
apply=0
online=0
build_java=0
selected=''
order_file=''
designer_workspace=''

usage() {
  cat <<'USAGE'
Usage: bash MoveToIS.sh [options]
  --repo-root DIR      Parent containing cloned repositories (default: current DIR)
  --packages-dir DIR   Local IS packages directory
  --package NAME      Process only this inner NBK package
  --order FILE        Package names in dependency order, one per line; # comments OK
                      When supplied, only the packages in this file are processed.
  --designer-workspace DIR  Enable linked resources in this Designer workspace with --apply
                           Close Designer before applying this workspace change.
  --apply             Create junctions and apply requested preferences
  --online            With --apply, request activation, enable, and reload on local IS
  --build-java        With --apply, run the instance bin/jcode.bat make for each package
  --url URL           Local IS URL (default: http://localhost:4455)
  --user USER         IS administrator username; password is prompted once
  --help

Expected source: REPO_ROOT/<repository>/NBK<package>/manifest.v3
This script does not import or configure Eclipse/Designer projects.
USAGE
}
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
require_value() { [[ $# -ge 2 && -n "$2" ]] || die "Missing value for $1"; }
while (( $# )); do
  case "$1" in
    --repo-root) require_value "$@"; repo_root="$2"; shift 2 ;;
    --packages-dir) require_value "$@"; packages_dir="$2"; shift 2 ;;
    --package) require_value "$@"; selected="$2"; shift 2 ;;
    --order) require_value "$@"; order_file="$2"; shift 2 ;;
    --url) require_value "$@"; server_url="${2%/}"; shift 2 ;;
    --user) require_value "$@"; is_user="$2"; shift 2 ;;
    --designer-workspace) require_value "$@"; designer_workspace="$2"; shift 2 ;;
    --apply) apply=1; shift ;;
    --online) online=1; shift ;;
    --build-java) build_java=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done
command -v git >/dev/null || die 'Git is required.'
[[ -d "$repo_root" ]] || die "Repository parent does not exist: $repo_root"
[[ -d "$packages_dir" ]] || die "IS packages directory does not exist: $packages_dir"
repo_root="$(cd "$repo_root" && pwd -P)"
packages_dir="$(cd "$packages_dir" && pwd -P)"
[[ -z "$selected" || "$selected" =~ ^NBK[A-Za-z0-9_.-]*$ ]] || die 'Invalid NBK package name.'
[[ -z "$order_file" || -z "$selected" ]] || die 'Use either --package or --order.'
[[ "$server_url" =~ ^https?://(localhost|127\.0\.0\.1)(:[0-9]+)?$ ]] || die '--url must identify the local IS (localhost or 127.0.0.1).'

names=()
sources=()
repositories=()
for repo in "$repo_root"/*/; do
  for candidate in "$repo"NBK*/; do
    [[ -f "$candidate/manifest.v3" ]] || continue
    name="$(basename "$candidate")"
    [[ -z "$selected" || "$name" == "$selected" ]] || continue
    [[ "$name" =~ ^NBK[A-Za-z0-9_.-]*$ ]] || die "Invalid package name: $name"
    src="$(cd "$candidate" && pwd -P)"
    git_root="$(git -C "$src" rev-parse --show-toplevel 2>/dev/null)" || die "Not inside a Git working tree: $src"
    # Windows package names are case-insensitive; reject ambiguous targets.
    for existing in "${names[@]}"; do
      [[ "${existing,,}" != "${name,,}" ]] || die "Duplicate package name across repositories: $name"
    done
    names+=("$name")
    sources+=("$src")
    repositories+=("$git_root")
  done
done
(( ${#names[@]} )) || die 'No inner NBK package with manifest.v3 was found.'

indices=()
if [[ -n "$order_file" ]]; then
  [[ -f "$order_file" ]] || die "Order file does not exist: $order_file"
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -n "$line" ]] || continue
    index=''
    for i in "${!names[@]}"; do [[ "${names[i]}" != "$line" ]] || index="$i"; done
    [[ -n "$index" ]] || die "Package from order file not found: $line"
    for previous in "${indices[@]}"; do [[ "$previous" != "$index" ]] || die "Repeated package in order file: $line"; done
    indices+=("$index")
  done < "$order_file"
  (( ${#indices[@]} )) || die 'The order file contains no packages.'
else
  indices=("${!names[@]}")
fi

if [[ -n "$designer_workspace" ]]; then
  settings="$designer_workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings"
  current="$settings/com.softwareag.is.core.iscomm.prefs"
  legacy="$settings/com.softwareag.is.vcsintegration.showfiles.prefs"
  [[ -f "$current" ]] || die "Designer preferences not found: $current"
  printf 'Designer: enable linked-resource preference in %s\n' "$designer_workspace"
fi

update_designer_preference() (
  local file="$1" value="$2" temporary
  [[ -f "$file" ]] || return 0
  temporary="$(mktemp "$file.movetois.XXXXXXXX")" || return 1
  trap 'rm -f -- "$temporary"' EXIT
  awk -v value="$value" '
    BEGIN { newline = "\n" }
    NR == 1 && /\r$/ { newline = "\r\n" }
    !/^[ \t]*symboliclink[.]message([ \t:=]|\r?$)/ { print }
    END { if (value != "") printf "symboliclink.message=%s%s", value, newline }
  ' "$file" > "$temporary" || return 1
  if ! cmp -s "$file" "$temporary"; then
    if [[ ! -e "$file.movetois.bak" ]]; then
      cp -p -- "$file" "$file.movetois.bak" || return 1
    fi
    mv -f -- "$temporary" "$file" || return 1
  fi
)

blocked=0
for i in "${indices[@]}"; do
  dst="$packages_dir/${names[i]}"
  printf '\nPACKAGE %s\n  Git:    %s\n  Source: %s\n  IS:     %s\n' "${names[i]}" "${repositories[i]}" "${sources[i]}" "$dst"
  if [[ -e "$dst" || -L "$dst" ]]; then
    if [[ "$dst" -ef "${sources[i]}" ]]; then
      printf '  Link: already resolves to the source directory\n'
    else
      printf '  BLOCKED: target already exists and is not the source directory\n'
      blocked=1
    fi
  else
    printf '  Link: create a Windows junction\n'
  fi
  [[ -f "${sources[i]}/.project" ]] || printf '  NOTE: .project missing; Designer project setup is required\n'
done
(( blocked == 0 )) || die 'Resolve existing target folders first. Nothing was changed. See README.'
if (( ! apply )); then
  printf '\nDRY RUN: no files changed and no IS requests made. Add --apply to create junctions.\n'
  exit 0
fi
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) ;; *) die '--apply requires Windows Git Bash.' ;; esac
for tool in cygpath cmd.exe; do command -v "$tool" >/dev/null || die "Missing command: $tool"; done
if (( online )); then
  command -v curl >/dev/null || die 'curl is required for --online.'
  [[ "$is_user" != *$'\n'* && "$is_user" != *$'\r'* ]] || die 'Invalid username.'
  IFS= read -r -s -p "IS password for $is_user: " is_password
  printf '\n'
  [[ -n "$is_password" && "$is_password" != *$'\r'* ]] || die 'Password cannot be empty or contain carriage returns.'
  # Supply authentication through curl stdin; do not put it in command arguments.
  curl_auth="$is_user:$is_password"
  curl_auth="${curl_auth//\\/\\\\}"
  curl_auth="${curl_auth//\"/\\\"}"
  unset is_password
fi

cmd_path() {
  local converted
  converted="$(cygpath -aw "$1")" || return 1
  # cmd.exe expands these even in some quoted contexts; refuse ambiguous paths.
  [[ "$converted" != *[\"\%\!\^\&\|\<\>]* && "$converted" != *$'\n'* && "$converted" != *$'\r'* ]] || die "Unsupported cmd.exe path: $1"
  printf '%s' "$converted"
}
# Validate all Windows paths before creating the first junction.
for i in "${indices[@]}"; do
  cmd_path "${sources[i]}" >/dev/null
  cmd_path "$packages_dir/${names[i]}" >/dev/null
done
if (( build_java )); then
  jcode="$packages_dir/../bin/jcode.bat"
  [[ -f "$jcode" ]] || die "jcode.bat was not found: $jcode"
  jcode_win="$(cmd_path "$jcode")"
fi

umask 077
log_dir="$(mktemp -d "$PWD/MoveToIS-logs-XXXXXXXX")"
printf '\nResponse and build logs: %s\n' "$log_dir"
for i in "${indices[@]}"; do
  dst="$packages_dir/${names[i]}"
  [[ "$dst" -ef "${sources[i]}" ]] && continue
  # Recheck at the moment of creation; never replace an existing directory.
  [[ ! -e "$dst" && ! -L "$dst" ]] || die "Target appeared during processing: $dst"
  dst_win="$(cmd_path "$dst")"
  src_win="$(cmd_path "${sources[i]}")"
  MOVETOIS_LINK="\"$dst_win\"" MOVETOIS_TARGET="\"$src_win\"" \
    MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
    cmd.exe /d /v:off /s /c 'mklink /J %MOVETOIS_LINK% %MOVETOIS_TARGET%' \
    > "$log_dir/${names[i]}-junction.log" 2>&1 \
    || die "Junction creation failed; see $log_dir/${names[i]}-junction.log"
  [[ "$dst" -ef "${sources[i]}" ]] || die "Cannot confirm junction identity for $dst. Inspect it before continuing."
  printf 'LINKED %s\n' "${names[i]}"
done
if [[ -n "$designer_workspace" ]]; then
  update_designer_preference "$current" true || die "Could not update $current"
  update_designer_preference "$legacy" '' || die "Could not update $legacy"
  printf 'Enabled Designer linked-resource preference.\n'
fi
if (( build_java )); then
  for i in "${indices[@]}"; do
    MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' cmd.exe /d /c "call \"$jcode_win\" make \"${names[i]}\"" > "$log_dir/${names[i]}-jcode.log" 2>&1 || die "jcode failed; see $log_dir/${names[i]}-jcode.log"
    printf 'BUILD COMMAND FINISHED %s (review jcode log)\n' "${names[i]}"
  done
fi
if (( online )); then
  printf 'Sending internal IS requests. HTTP success alone does not prove package load success.\n'
  for i in "${indices[@]}"; do
    for operation in packageActivate packageEnable packageReload; do
      response="$log_dir/${names[i]}-$operation.html"
      printf 'user = "%s"\n' "$curl_auth" | curl --config - --noproxy '*' --silent --show-error --fail \
        --connect-timeout 10 --max-time 120 --get --data-urlencode "package=${names[i]}" \
        "$server_url/invoke/wm.server.packages:$operation" --output "$response" || die "HTTP request failed: ${names[i]} $operation. Check $response and IS logs."
      # IS can return application errors with HTTP 200. Flag known error forms.
      plain="$(sed -E 's/<[^>]*>/ /g' "$response" | tr '\r\n\t' '   ')"
      if printf '%s' "$plain" | grep -Eiq '(unknown package|access denied|unauthori[sz]ed|exception|errorMessage|code[[:space:]"=:]+-[0-9])'; then
        die "IS reported an error: ${names[i]} $operation. Read $response."
      fi
      printf 'HTTP OK %s %s; response saved\n' "${names[i]}" "$operation"
    done
  done
  unset curl_auth
fi
printf '\nJunction processing finished. Verify loaded/enabled status in IS, refresh Designer,\nthen save one service and check git status in its original repository.\n'
