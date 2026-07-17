#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <apk> <version-name> <version-code> <release-notes>" >&2
  echo "       $0 --validate <version-name> <version-code> <release-notes>" >&2
}

validate_release_inputs() {
  local version_name="$1"
  local version_code="$2"
  local release_notes="$3"

  if [[ ! "$version_name" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
    echo "version-name must use stable semantic version format, for example 1.2.3" >&2
    return 1
  fi
  if [[ ! "$version_code" =~ ^[1-9][0-9]*$ ]] ||
    (( ${#version_code} > 10 )) ||
    { (( ${#version_code} == 10 )) && [[ "$version_code" > "2100000000" ]]; }; then
    echo "version-code must be an integer between 1 and 2100000000" >&2
    return 1
  fi
  if [[ ! "$release_notes" =~ [^[:space:]] ]]; then
    echo "release-notes must not be blank" >&2
    return 1
  fi
  if (( ${#release_notes} > 2000 )); then
    echo "release-notes must not exceed 2000 characters" >&2
    return 1
  fi
}

if [[ "${1:-}" == "--validate" ]]; then
  if [[ $# -ne 4 ]]; then
    usage
    exit 2
  fi
  validate_release_inputs "$2" "$3" "$4"
  exit 0
fi

if [[ $# -ne 4 ]]; then
  usage
  exit 2
fi

apk_path="$1"
version_name="$2"
version_code="$3"
release_notes="$4"

validate_release_inputs "$version_name" "$version_code" "$release_notes"

if [[ ! -s "$apk_path" ]]; then
  echo "APK does not exist or is empty: $apk_path" >&2
  exit 1
fi

: "${CLOUDFLARE_ACCOUNT_ID:?missing CLOUDFLARE_ACCOUNT_ID}"
: "${R2_ACCESS_KEY_ID:?missing R2_ACCESS_KEY_ID}"
: "${R2_SECRET_ACCESS_KEY:?missing R2_SECRET_ACCESS_KEY}"
: "${R2_BUCKET_NAME:?missing R2_BUCKET_NAME}"

for required_command in aws curl jq sha256sum sort stat; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "missing required command: $required_command" >&2
    exit 1
  fi
done

public_base_url="${PUBLIC_BASE_URL:-https://app.xmoyi.com}"
public_base_url="${public_base_url%/}"
if [[ ! "$public_base_url" =~ ^https://[^/]+$ ]]; then
  echo "PUBLIC_BASE_URL must be an HTTPS origin without a path" >&2
  exit 1
fi

endpoint="https://${CLOUDFLARE_ACCOUNT_ID}.r2.cloudflarestorage.com"
apk_name="nainaisv-${version_name}.apk"
apk_key="nainaisv/releases/${apk_name}"
manifest_key="nainaisv/stable/update.json"
apk_url="${public_base_url}/${apk_key}"
apk_size="$(stat -c '%s' "$apk_path")"
apk_sha256="$(sha256sum "$apk_path" | awk '{print $1}')"
published_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
temp_dir="$(mktemp -d)"
manifest_file="$temp_dir/update.json"
current_manifest_file="$temp_dir/current-update.json"
public_manifest_file="$temp_dir/public-update.json"
head_error_file="$temp_dir/head-error.txt"
head_output_file="$temp_dir/head-output.json"
apk_uploaded=false
manifest_published=false
manifest_write_condition=(--if-none-match '*')

export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"
export AWS_DEFAULT_REGION="auto"

cleanup() {
  if [[ "$apk_uploaded" == "true" && "$manifest_published" != "true" ]]; then
    echo "Publication failed before update.json was changed; removing the newly uploaded APK" >&2
    aws s3 rm "s3://${R2_BUCKET_NAME}/${apk_key}" --endpoint-url "$endpoint" >/dev/null || true
  fi
  rm -rf "$temp_dir"
}
trap cleanup EXIT

head_object() {
  local object_key="$1"
  : > "$head_error_file"
  : > "$head_output_file"
  aws s3api head-object \
    --bucket "$R2_BUCKET_NAME" \
    --key "$object_key" \
    --endpoint-url "$endpoint" \
    >"$head_output_file" 2>"$head_error_file"
}

is_missing_object_error() {
  grep -Eq '(404|Not Found|NoSuchKey)' "$head_error_file"
}

if ! curl --silent --show-error --head --max-time 20 "$public_base_url/" >/dev/null; then
  echo "PUBLIC_BASE_URL is not reachable over HTTPS: $public_base_url" >&2
  exit 1
fi

if head_object "$apk_key"; then
  echo "Refusing to overwrite existing immutable APK: s3://${R2_BUCKET_NAME}/${apk_key}" >&2
  exit 1
elif ! is_missing_object_error; then
  cat "$head_error_file" >&2
  echo "Unable to determine whether the APK already exists" >&2
  exit 1
fi

if head_object "$manifest_key"; then
  current_manifest_etag="$(jq -er '.ETag' "$head_output_file")"
  manifest_write_condition=(--if-match "$current_manifest_etag")
  aws s3 cp "s3://${R2_BUCKET_NAME}/${manifest_key}" "$current_manifest_file" \
    --endpoint-url "$endpoint" \
    --only-show-errors
  current_version_code="$(jq -er '.versionCode | select(type == "number" and floor == . and . >= 1)' "$current_manifest_file")"
  current_version_name="$(jq -er '.versionName | select(type == "string")' "$current_manifest_file")"
  if (( version_code <= current_version_code )); then
    echo "version-code must increase: current=$current_version_code requested=$version_code" >&2
    exit 1
  fi
  if [[ ! "$current_version_name" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
    echo "Current manifest has an invalid semantic version: $current_version_name" >&2
    exit 1
  fi
  highest_version="$(printf '%s\n%s\n' "$current_version_name" "$version_name" | sort -V | tail -n 1)"
  if [[ "$highest_version" != "$version_name" || "$current_version_name" == "$version_name" ]]; then
    echo "version-name must increase: current=$current_version_name requested=$version_name" >&2
    exit 1
  fi
elif ! is_missing_object_error; then
  cat "$head_error_file" >&2
  echo "Unable to read the current update manifest" >&2
  exit 1
fi

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

aws s3api put-object \
  --bucket "$R2_BUCKET_NAME" \
  --key "$apk_key" \
  --body "$apk_path" \
  --endpoint-url "$endpoint" \
  --content-type "application/vnd.android.package-archive" \
  --cache-control "public, max-age=31536000, immutable" \
  --metadata "sha256=${apk_sha256},version-code=${version_code},version-name=${version_name}" \
  --if-none-match '*' \
  >/dev/null
apk_uploaded=true

remote_head="$(aws s3api head-object \
  --bucket "$R2_BUCKET_NAME" \
  --key "$apk_key" \
  --endpoint-url "$endpoint" \
  --query '{size:ContentLength,sha256:Metadata.sha256}' \
  --output json)"
remote_size="$(jq -er '.size' <<<"$remote_head")"
remote_sha256="$(jq -er '.sha256' <<<"$remote_head")"

if [[ "$remote_size" != "$apk_size" ]]; then
  echo "R2 size mismatch: local=$apk_size remote=$remote_size" >&2
  exit 1
fi
if [[ "$remote_sha256" != "$apk_sha256" ]]; then
  echo "R2 SHA-256 metadata mismatch: local=$apk_sha256 remote=$remote_sha256" >&2
  exit 1
fi

curl --fail --silent --show-error --head --retry 3 --retry-delay 2 --max-time 30 "$apk_url" >/dev/null

aws s3api put-object \
  --bucket "$R2_BUCKET_NAME" \
  --key "$manifest_key" \
  --body "$manifest_file" \
  --endpoint-url "$endpoint" \
  --content-type "application/json; charset=utf-8" \
  --cache-control "no-store, no-cache, max-age=0, must-revalidate" \
  "${manifest_write_condition[@]}" \
  >/dev/null
manifest_published=true

published_manifest_sha256="$(sha256sum "$manifest_file" | awk '{print $1}')"
aws s3 cp "s3://${R2_BUCKET_NAME}/${manifest_key}" "$current_manifest_file" \
  --endpoint-url "$endpoint" \
  --only-show-errors
remote_manifest_sha256="$(sha256sum "$current_manifest_file" | awk '{print $1}')"
if [[ "$remote_manifest_sha256" != "$published_manifest_sha256" ]]; then
  echo "R2 update manifest content mismatch" >&2
  exit 1
fi

public_manifest_verified=false
for attempt in 1 2 3 4 5; do
  if curl --fail --silent --show-error \
    --header "Cache-Control: no-cache" \
    --max-time 30 \
    "${public_base_url}/${manifest_key}" \
    --output "$public_manifest_file" && \
    jq -e \
      --argjson versionCode "$version_code" \
      --arg versionName "$version_name" \
      --arg apkUrl "$apk_url" \
      --arg sha256 "$apk_sha256" \
      --argjson size "$apk_size" \
      '.versionCode == $versionCode and .versionName == $versionName and .apkUrl == $apkUrl and .sha256 == $sha256 and .size == $size' \
      "$public_manifest_file" >/dev/null; then
    public_manifest_verified=true
    break
  fi
  sleep 2
done

if [[ "$public_manifest_verified" != "true" ]]; then
  echo "The public update manifest did not return the newly published release" >&2
  exit 1
fi

echo "published $apk_url"
echo "sha256 $apk_sha256"
