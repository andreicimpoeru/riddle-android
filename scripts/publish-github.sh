#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="${HOME}/.local/gh/usr/bin:${HOME}/.local/git/usr/bin:${PATH}"

REPO_NAME="${1:-riddle-android}"
VISIBILITY="${2:-public}"

cd "$ROOT"

if ! gh auth status >/dev/null 2>&1; then
  echo "Not logged into GitHub. Run:"
  echo "  export PATH=\"\$HOME/.local/gh/usr/bin:\$PATH\""
  echo "  gh auth login --hostname github.com --git-protocol https --web --scopes repo"
  exit 1
fi

if git remote get-url origin >/dev/null 2>&1; then
  echo "Remote origin already set:"
  git remote -v
else
  gh repo create "$REPO_NAME" --source=. --remote=origin --"$VISIBILITY" --description "Riddle diary for Boox/Android e-ink tablets"
fi

git push -u origin main
echo "Published: $(gh repo view --json url -q .url)"