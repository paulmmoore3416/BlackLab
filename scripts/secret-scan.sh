#!/usr/bin/env bash
# Fails if anything that looks like a credential or signing material is tracked by git.
# Runs in CI on every push and pull request; run it locally before pushing:  scripts/secret-scan.sh
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

fail=0
report() { echo "::error::$1"; fail=1; }

# 1. Files that must never be committed (signing keys, keystore passwords, service configs, env files)
bad_files=$(git ls-files | grep -Ei '(^|/)keystore/|\.jks$|\.keystore$|\.p12$|\.pfx$|\.pem$|(^|/)keystore\.properties$|(^|/)signing\.properties$|(^|/)secrets\.properties$|(^|/)google-services\.json$|(^|/)\.env(\..*)?$|(^|/)local\.properties$' || true)
if [ -n "$bad_files" ]; then
  report "Tracked files that must stay private:"; echo "$bad_files"
fi

# 2. Credential-shaped strings in tracked text files (binary files such as the release APKs are skipped)
patterns=(
  'AIza[0-9A-Za-z_-]{35}'                              # Google API key
  'GOCSPX-[0-9A-Za-z_-]{20,}'                          # Google OAuth client secret
  'ya29\.[0-9A-Za-z_-]{20,}'                           # Google OAuth access token
  'gh[pousr]_[A-Za-z0-9]{36,}'                         # GitHub token
  'github_pat_[A-Za-z0-9_]{50,}'                       # GitHub fine-grained token
  'sk_[a-f0-9]{48}'                                    # ElevenLabs API key
  'sk-ant-[A-Za-z0-9_-]{20,}'                          # Anthropic API key
  'sk-(proj-)?[A-Za-z0-9]{32,}'                        # OpenAI API key
  'AKIA[0-9A-Z]{16}'                                   # AWS access key
  'xox[baprs]-[0-9A-Za-z-]{10,}'                       # Slack token
  '-----BEGIN ([A-Z]+ )?PRIVATE KEY-----'              # Private keys
  '(store|key)Password=[A-Za-z0-9!@#%^&*_+.-]{4,}'    # keystore.properties-style signing passwords
)
for p in "${patterns[@]}"; do
  hits=$(git grep -nIE -e "$p" -- . ':!scripts/secret-scan.sh' || true)
  if [ -n "$hits" ]; then
    report "Possible secret matching /$p/:"; echo "$hits" | cut -c1-200
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "Secret scan FAILED. Remove the values above (and rotate them if they were ever pushed)."
  exit 1
fi
echo "Secret scan passed: $(git ls-files | wc -l | tr -d ' ') tracked files checked."
