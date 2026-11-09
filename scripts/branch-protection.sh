#!/bin/bash
# Protects main and develop: no direct push, PR required, Jenkins check must be green.
# Run AFTER the first PR build (the check name only exists after Jenkins reports once).
# Usage: ./branch-protection.sh <owner> <repo> [required-reviewers]
#   Solo practice: leave reviewers at 0 (you cannot approve your own PR).
#   Real team: use 1 or 2.
set -e
OWNER=${1:?owner}
REPO=${2:?repo}
REVIEWERS=${3:-0}

cat > protection.json <<JSON
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["continuous-integration/jenkins/pr-merge"]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": { "required_approving_review_count": $REVIEWERS },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON

for BRANCH in main develop; do
  gh api "repos/$OWNER/$REPO/branches/$BRANCH/protection" --method PUT --input protection.json > /dev/null
  echo "Protected: $BRANCH"
done
rm -f protection.json
