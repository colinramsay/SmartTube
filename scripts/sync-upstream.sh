#!/usr/bin/env bash
# Pull the latest upstream SmartTube into master, then rebase our custom branch on top.
set -euo pipefail

UPSTREAM_URL="https://github.com/yuliskov/SmartTube.git"
BASE_BRANCH="master"
WORK_BRANCH="custom"

cd "$(git rev-parse --show-toplevel)"

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "Your working tree has uncommitted changes. Commit or stash them first." >&2
    exit 1
fi

if ! git remote get-url upstream >/dev/null 2>&1; then
    git remote add upstream "$UPSTREAM_URL"
fi

echo "Fetching upstream..."
git fetch upstream

echo "Fast-forwarding $BASE_BRANCH to upstream/$BASE_BRANCH..."
git checkout -q "$BASE_BRANCH"
if ! git merge --ff-only "upstream/$BASE_BRANCH"; then
    echo "$BASE_BRANCH has commits that are not in upstream. It must mirror upstream exactly." >&2
    echo "Move those commits to $WORK_BRANCH, then run: git reset --hard upstream/$BASE_BRANCH" >&2
    exit 1
fi

echo "Updating submodules to the commits upstream pins..."
git submodule sync --recursive
git submodule update --init --recursive

echo "Rebasing $WORK_BRANCH onto $BASE_BRANCH..."
git checkout -q "$WORK_BRANCH"
if ! git rebase "$BASE_BRANCH"; then
    cat >&2 <<MSG

Rebase stopped on a conflict.
Our edits to upstream files are tagged with LOCALRECS, so start with:
    git diff --name-only --diff-filter=U
    grep -rn LOCALRECS \$(git diff --name-only --diff-filter=U)
Fix the files, then run "git add <file>" and "git rebase --continue".
To give up and go back to where you were: git rebase --abort
MSG
    exit 1
fi

# The rebase can move the submodule pointers, so sync them again.
git submodule update --init --recursive

echo
echo "Done. $WORK_BRANCH is now based on $(git rev-parse --short "$BASE_BRANCH")."
echo "Push with: git push origin $BASE_BRANCH && git push --force-with-lease origin $WORK_BRANCH"
