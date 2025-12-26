#!/usr/bin/env bash
set -euo pipefail

# Fetch and unpack the Wasmtime C API prebuilt for Android arm64-v8a, then build the app.
# Usage: ./scripts/fetch_wasmtime_prebuilt.sh [--skip-build]

VERSION="v40.0.0"
ARCHIVE="wasmtime-${VERSION}-aarch64-android-c-api.tar.xz"
SHA256_EXPECTED="2b1c800950307db336d8952fa048110dac99e27e957b8770b5fd39c371ed1419"
DOWNLOAD_URL="https://github.com/bytecodealliance/wasmtime/releases/download/${VERSION}/${ARCHIVE}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREBUILT_DIR="${PROJECT_ROOT}/third_party/wasmtime-prebuilt"
SKIP_BUILD="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build)
      SKIP_BUILD="true"
      shift
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

mkdir -p "${PROJECT_ROOT}/third_party"
cd "${PROJECT_ROOT}/third_party"

if [[ ! -f "${ARCHIVE}" ]]; then
  echo "Downloading ${ARCHIVE}..."
  curl -fL -o "${ARCHIVE}" "${DOWNLOAD_URL}"
else
  echo "Archive already present: ${ARCHIVE}"
fi

echo "Verifying checksum..."
printf "%s  %s\n" "${SHA256_EXPECTED}" "${ARCHIVE}" | shasum -a 256 -c -

echo "Extracting to ${PREBUILT_DIR}..."
rm -rf "${PREBUILT_DIR}"
mkdir -p "${PREBUILT_DIR}"
tar -xJf "${ARCHIVE}" -C "${PREBUILT_DIR}" --strip-components=1

echo "Exporting WASMTIME_SDK_DIR=${PREBUILT_DIR}"
export WASMTIME_SDK_DIR="${PREBUILT_DIR}"

if [[ "${SKIP_BUILD}" == "true" ]]; then
  echo "Skipping Gradle build (per --skip-build)."
  exit 0
fi

cd "${PROJECT_ROOT}"
./gradlew :app:assembleDebug

echo "Done. APKs at app/build/outputs/."
