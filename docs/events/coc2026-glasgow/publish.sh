#!/usr/bin/env bash
#
# Publish site/ to the gh-pages branch of adityamparikh/solr-mcp and verify the
# change is actually live before reporting success.
#
#   ./publish.sh
#
# The live page is what attendees scan, so this checks the deployed bytes rather
# than trusting the push. GitHub Pages takes ~10-60s to serve a new commit.

set -euo pipefail

REPO="git@github.com:adityamparikh/solr-mcp.git"
REPO_HTTPS="https://github.com/adityamparikh/solr-mcp.git"
BRANCH="gh-pages"
LIVE="https://adityamparikh.github.io/solr-mcp/"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SITE="$HERE/site"

[ -d "$SITE" ] || { echo "error: $SITE not found"; exit 1; }
[ -f "$SITE/index.html" ] || { echo "error: $SITE/index.html not found"; exit 1; }

# A marker that changes whenever the card changes, so the liveness check below
# is testing *this* deploy rather than any old successful one.
STAMP="$(shasum -a 256 "$SITE/index.html" | cut -c1-12)"
echo "==> publishing site/ (index.html sha ${STAMP})"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Shallow-clone just the branch. Falls back to HTTPS if no SSH key is loaded.
git clone --quiet --depth 1 --branch "$BRANCH" "$REPO" "$WORK/repo" 2>/dev/null \
  || git clone --quiet --depth 1 --branch "$BRANCH" "$REPO_HTTPS" "$WORK/repo"

# Mirror site/ into the checkout: --delete so removing a local file removes it
# live too, but keep .git.
rsync -a --delete --exclude '.git' "$SITE/" "$WORK/repo/"

cd "$WORK/repo"
if git diff --quiet && [ -z "$(git status --porcelain)" ]; then
  echo "==> no changes; live site already matches site/"
  exit 0
fi

git add -A
git commit --quiet -m "Update hackathon card and slides (${STAMP})"
git push --quiet origin "$BRANCH"
echo "==> pushed"

# GitHub Pages serves the old bytes for a while after the push. Poll for the new
# ones rather than declaring victory immediately.
echo -n "==> waiting for $LIVE to serve the new build "
for _ in $(seq 1 40); do
  LIVE_SHA="$(curl -fsS --max-time 10 "$LIVE" 2>/dev/null | shasum -a 256 | cut -c1-12 || true)"
  if [ "$LIVE_SHA" = "$STAMP" ]; then
    echo
    echo "==> LIVE and verified: $LIVE"
    exit 0
  fi
  echo -n "."
  sleep 5
done

echo
echo "!! pushed, but $LIVE has not served the new build within ~3min."
echo "   Check https://github.com/adityamparikh/solr-mcp/actions (pages-build-deployment)."
exit 1
