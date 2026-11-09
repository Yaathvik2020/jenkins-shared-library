#!/bin/bash
# Creates the GitHub webhook (push + pull_request) pointing to Jenkins.
# Needs: GitHub CLI (gh) logged in.  Usage: ./create-webhook.sh <owner> <repo> <public-jenkins-url>
set -e
OWNER=${1:?owner}
REPO=${2:?repo}
JENKINS_URL=${3:?public jenkins url, e.g. https://abcd.ngrok-free.app}

gh api "repos/$OWNER/$REPO/hooks" \
  --method POST \
  -f name='web' \
  -F active=true \
  -f 'events[]=push' \
  -f 'events[]=pull_request' \
  -f "config[url]=${JENKINS_URL%/}/github-webhook/" \
  -f 'config[content_type]=json'

echo "Webhook created. Check GitHub -> Settings -> Webhooks -> Recent Deliveries"
