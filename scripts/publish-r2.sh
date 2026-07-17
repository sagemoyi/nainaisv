#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 ]]; then
  echo "usage: $0 <apk> <version-name> <version-code> <release-notes>" >&2
  exit 2
fi

apk_path="$1"
version_name="$2"
version_code="$3"
release_notes="$4"

: "${CLOUDFLARE_ACCOUNT_ID:?missing CLOUDFLARE_ACCOUNT_ID}"
: "${R2_ACCESS_KEY_ID:?missing R2_ACCESS_KEY_ID}"
: "${R2_SECRET_ACCESS_KEY:?missing R2_SECRET_ACCESS_KEY}"
: "${R2_BUCKET_NAME:?missing R2_BUCKET_NAME}"

public_base_url="${PUBLIC_BASE_URL:-https://app.xmoyi.com}"
endpoint="https://${CLOUDFLARE_ACCOUNT_ID}.r2.cloudflarestorage.com"
apk_name="nainaisv-${version_name}.apk"
apk_key="nainaisv/releases/${apk_name}"
manifest_key="nainaisv/stable/update.json"
apk_url="${public_base_url}/${apk_key}"
apk_size="$(stat -c '%s' "$apk_path")"
apk_sha256="$(sha256sum "$apk_path" | awk '{print $1}')"
published_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
manifest_file="$(mktemp)"
trap 'rm -f "$manifest_file"' EXIT

export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"
export AWS_DEFAULT_REGION="auto"

jq -n \
  --argjson versionCode "$version_code" \
  --arg versionName "$version_name" \
  --argjson minSupportedVersionCode 1 \
  --arg apkUrl "$apk_url" \
  --arg sha256 "$apk_sha256" \
  --argjson size "$apk_size" \
  --arg publishedAt "$published_at" \
  --arg releaseNotes "$release_notes" \
  '{versionCode:$versionCode,versionName:$versionName,minSupportedVersionCode:$minSupportedVersionCode,apkUrl:$apkUrl,sha256:$sha256,size:$size,publishedAt:$publishedAt,releaseNotes:$releaseNotes}' \
  > "$manifest_file"

aws s3 cp "$apk_path" "s3://${R2_BUCKET_NAME}/${apk_key}" \
  --endpoint-url "$endpoint" \
  --content-type "application/vnd.android.package-archive" \
  --cache-control "public, max-age=31536000, immutable"

remote_size="$(aws s3api head-object \
  --bucket "$R2_BUCKET_NAME" \
  --key "$apk_key" \
  --endpoint-url "$endpoint" \
  --query ContentLength \
  --output text)"

if [[ "$remote_size" != "$apk_size" ]]; then
  echo "R2 size mismatch: local=$apk_size remote=$remote_size" >&2
  exit 1
fi

aws s3 cp "$manifest_file" "s3://${R2_BUCKET_NAME}/${manifest_key}" \
  --endpoint-url "$endpoint" \
  --content-type "application/json; charset=utf-8" \
  --cache-control "no-cache, max-age=0"

curl --fail --silent --show-error --head "${public_base_url}/${manifest_key}" >/dev/null
echo "published ${apk_url}"
