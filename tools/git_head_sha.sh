#!/bin/sh
# Print a 12-character git HEAD sha without requiring the git binary.
#
# Used by the Makefile when building inside containers that only have the
# checkout mounted (devkitpro/devkitarm has no git package). Handles:
#   - a normal .git directory (refs/heads/<branch> or packed-refs)
#   - a .git file ("gitdir: ..."), as used by linked worktrees
#   - a detached HEAD
# Exits non-zero when HEAD cannot be resolved; callers fall back to "unknown".
set -eu

root=${1:-.}
gitdir="$root/.git"
if [ -f "$gitdir" ]; then
    line=$(cat "$gitdir")
    case "$line" in
    "gitdir: "*) gitdir=${line#gitdir: } ;;
    *)
        echo "git_head_sha: unsupported .git file" >&2
        exit 1
        ;;
    esac
fi
[ -d "$gitdir" ] || exit 1

# Linked worktrees keep refs in the common directory, only HEAD is local.
common="$gitdir"
if [ -f "$gitdir/commondir" ]; then
    rel=$(cat "$gitdir/commondir")
    case "$rel" in
    /*) common="$rel" ;;
    *) common="$gitdir/$rel" ;;
    esac
fi

head=$(cat "$gitdir/HEAD" 2>/dev/null) || exit 1
case "$head" in
"ref: "*)
    ref=${head#ref: }
    if [ -f "$common/$ref" ]; then
        sha=$(cat "$common/$ref")
    elif [ -f "$common/packed-refs" ]; then
        sha=$(awk -v r="$ref" '$2 == r { print $1; exit }' "$common/packed-refs")
    else
        sha=""
    fi
    ;;
*)
    # Detached HEAD stores the sha directly.
    sha=$head
    ;;
esac

[ -n "${sha:-}" ] || exit 1
printf '%s\n' "$sha" | cut -c1-12
