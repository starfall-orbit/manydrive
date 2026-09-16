#!/usr/bin/env bash
set -euo pipefail

GITHUB_RELEASE_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
: "${GITHUB_RELEASE_TOKEN:?Set secret GITHUB_TOKEN (or GH_TOKEN) in Codemagic environment group github_release}"
export GH_TOKEN="$GITHUB_RELEASE_TOKEN"
repo="${GITHUB_RELEASE_REPO:-starfall-org/manydrive}"
cd "${CM_BUILD_DIR:-$(git rev-parse --show-toplevel)}"
command -v gh >/dev/null || { echo 'GitHub CLI (gh) is required.' >&2; exit 1; }

# Use the version embedded in the APK build, not a second version in CI configuration.
version="$(python3 -c 'import json; print(json.load(open("app/build/outputs/apk/release/output-metadata.json"))["elements"][0]["versionName"])')"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || { echo 'Invalid app version.' >&2; exit 1; }
tag="v$version"
if [[ -n "${CM_TAG:-}" && "$CM_TAG" != "$tag" ]]; then
  echo "Build tag $CM_TAG does not match app version $tag." >&2
  exit 1
fi
commit="$(git rev-parse HEAD)"
shopt -s nullglob
apks=(app/build/outputs/apk/release/*.apk)
aabs=(app/build/outputs/bundle/release/*.aab)
(( ${#apks[@]} > 0 && ${#aabs[@]} > 0 )) || { echo 'Missing release APKs or AAB.' >&2; exit 1; }

if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
  # A rerun may replace assets only for the same source commit.
  release_commit="$(gh api "repos/$repo/commits/$tag" --jq .sha)"
  [[ "$release_commit" == "$commit" ]] || { echo "Release $tag belongs to another commit. Increase the app version." >&2; exit 1; }
else
  # If a tag already exists without a release, it must also match this build.
  existing_commit="$(gh api "repos/$repo/commits/$tag" --jq .sha 2>/dev/null || true)"
  [[ -z "$existing_commit" || "$existing_commit" == "$commit" ]] || { echo "Tag $tag belongs to another commit." >&2; exit 1; }
  gh release create "$tag" --repo "$repo" --target "$commit" \
    --title "ManyDrive $version" --generate-notes --draft
fi

gh release upload "$tag" "${apks[@]}" "${aabs[@]}" --repo "$repo" --clobber
gh release edit "$tag" --repo "$repo" --draft=false
gh release view "$tag" --repo "$repo" --json url --jq .url
