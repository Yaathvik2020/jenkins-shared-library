# Jenkins Multibranch + PR CI/CD Guide

Nov 9, 2026 · @Yaathvik

## 1. Overview

Every change goes feature branch → PR → develop (DEV) → PR → main → approval → PROD, and Jenkins builds every step automatically. A PR is only built and tested; only merged code is deployed.

&#91;embedded content: whole flow · 3 branches, what Jenkins does on each\]

Each arrow between branches is a PR that must be green before merging; the dashed line is the back-merge that keeps develop equal to main.

**Three pieces, owned by different people**

| Piece | Lives in | Owned by | What it does |
| --- | --- | --- | --- |
| `demo-app` | GitHub repo | App developers | Java code + a 5-line Jenkinsfile |
| `jenkins-shared-library` | GitHub repo | DevOps team | The real pipeline steps, shared by all apps |
| `jenkins-platform` | Your PC (Docker) | DevOps team | Jenkins + image registry, configured as code |

**Branches**

| Branch | Lifetime | Purpose | Deploys to |
| --- | --- | --- | --- |
| `main` | Permanent | Production code | PROD (port 8082), after approval |
| `develop` | Permanent, default branch | Tested team code | DEV (port 8081) |
| `feature/<name>` | Short, deleted after merge | One change | Nothing |

## 2. One-time setup

You do this once; after that Jenkins rebuilds itself from these files. On Windows, run every command in **Git Bash**.

**Tools needed:** Docker Desktop (running, 4 GB+ RAM), Git, GitHub CLI `gh` (run `gh auth login` once), and ngrok only if you want webhooks.

1. **Stop Windows line-ending problems** (files run inside Linux):

   ```bash
   git config --global core.autocrlf input
   ```
2. **Create the shared library repo.** On GitHub create an empty repo `jenkins-shared-library`, then:

   ```bash
   cd jenkins-shared-library
   git init -b main
   git add . && git commit -m "Company Java pipeline"
   git remote add origin https://github.com/Yaathvik2020/jenkins-shared-library.git
   git push -u origin main
   ```
3. **Create the app repo with two branches.** Create an empty repo `demo-app` (no README), then:

   ```bash
   cd ../demo-app
   git init -b main
   git add . && git commit -m "Initial commit"
   git remote add origin https://github.com/Yaathvik2020/demo-app.git
   git push -u origin main
   git checkout -b develop
   git push -u origin develop
   ```

   Then on GitHub: **Settings → General → Default branch → develop**, so new PRs target develop.
4. **Create a GitHub token.** GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → tick `repo` and `admin:repo_hook`. Never paste it anywhere except `.env`.
5. **Fill in `.env`** in `jenkins-platform` (all 7 lines):

   ```
   GITHUB_OWNER=Yaathvik2020
   GITHUB_REPO=demo-app
   GITHUB_USER=Yaathvik2020
   GITHUB_TOKEN=<your token>
   ADMIN_PASSWORD=<admin password>
   RELEASE_PASSWORD=<release manager password>
   JENKINS_URL=http://localhost:8080/
   ```
6. **Start Jenkins:**

   ```bash
   cd ../jenkins-platform
   sed -i 's/\r$//' .env casc/* scripts/*.sh
   docker compose up -d --build
   docker compose logs -f jenkins
   ```

   Wait for `Jenkins is fully up and running`, then open http://localhost:8080 and log in as `admin`.
7. **Check the job.** The **demo-app** job is already there and finds `main` and `develop`. The first `main` build stops at **Approval for PROD**; approve it as `release-manager` or click Abort.

## 3. How Jenkins creates the job by itself

You never click New Item: on every start, Jenkins reads its config files and builds the job from code. Delete Jenkins with `docker compose down -v`, start it again, and the same job comes back.

&#91;embedded content: job creation · 5 steps from docker compose up to a build\]

Steps 1–3 run once at every start; steps 4–5 repeat every minute (or on each webhook).

| File | Simple meaning | What it does |
| --- | --- | --- |
| `plugins.txt` | Which tools to install | Read by `jenkins-plugin-cli` during `docker build`; plugins are baked into the image |
| `.env` | Your secret details | Loaded into the container as environment variables |
| `casc/jenkins.yaml` | The instruction sheet | Creates users `admin` and `release-manager`, saves the token as `github-creds`, registers the library `company-pipeline`, runs the job script |
| `casc/multibranch-job.groovy` | The job recipe | Job DSL creates the Multibranch job `demo-app`: watch GitHub, find branches + PRs, check every 1 minute |
| `Jenkinsfile` (in demo-app) | What to build | `@Library('company-pipeline')` + `javaPipeline(...)` |
| `vars/javaPipeline.groovy` (in library) | How to build | All the stages: build, test, Docker, deploy, approval |

**The discovery options in the job script** (built-in Job DSL methods; each is a checkbox in Configure → Branch Sources → Behaviours):

| Method | Value | Meaning |
| --- | --- | --- |
| `buildOriginBranch` | true | Build main, develop, feature/\* |
| `buildOriginBranchWithPR` | false | A branch with an open PR is built only as the PR (that's why it shows crossed out) |
| `buildOriginPRMerge` | true | Test each PR merged with its target branch |
| `buildOriginPRHead` | false | Don't also test the PR code alone |
| `buildForkPRMerge` / `buildForkPRHead` | false | Don't build PRs from other people's forks |

Every method a block accepts is listed at `http://localhost:8080/plugin/job-dsl/api-viewer/index.html`. A method not listed there (like `repositoryUrl()` inside the old `github { }` block) makes job creation fail at startup.

## 4. Protect main and develop

Branch protection blocks direct pushes to `main` and `develop` and blocks merging any PR until Jenkins is green. It does not start builds; it only controls merging.

**What the rules enforce**

- No direct `git push` to main or develop, even for admins (`enforce_admins: true`)
- Every change goes through a PR
- The check `continuous-integration/jenkins/pr-merge` must pass
- The PR must be up to date with its target (`strict: true`)
- No force pushes, no deleting the branch
- Reviewers: 0 when you practise alone (you can't approve your own PR); 1 or 2 in a real team

**Option A: script** (run after Jenkins has built at least one PR, so the check name exists)

```bash
cd jenkins-platform
sed -i 's/\r$//' scripts/*.sh && chmod +x scripts/*.sh
gh auth login
./scripts/branch-protection.sh Yaathvik2020 demo-app      # solo: 0 reviewers
./scripts/branch-protection.sh Yaathvik2020 demo-app 1    # team: 1 reviewer
```

**Option B: GitHub UI**

1. Repo → **Settings → Branches → Add branch protection rule** (or **Rulesets → New ruleset**).
2. Branch name pattern: `develop`.
3. Tick **Require a pull request before merging** (set approvals to 0 or 1).
4. Tick **Require status checks to pass** → **Require branches to be up to date** → search and add `continuous-integration/jenkins/pr-merge`.
5. Tick **Do not allow bypassing the above settings**. Leave force pushes and deletions off.
6. Save, then repeat for `main`.

**Check it works**

- `git push origin develop` from your PC is now rejected with `protected branch hook declined`.
- Open a PR with a broken test: GitHub shows a red X and the Merge button is blocked.
- To add another permanent branch (for example `test-env`), add it to the loop in the script: `for BRANCH in main develop test-env; do`.

## 5. Webhooks: instant builds

Without a webhook, Jenkins asks GitHub every 1 minute what changed (polling, from `periodicFolderTrigger { interval('1m') }`). With a webhook, GitHub tells Jenkins the moment you push or open a PR, so builds start in seconds.

&#91;embedded content: polling vs webhook · who starts the build\]

GitHub cannot reach localhost, so the webhook goes through ngrok's public URL to Jenkins' `/github-webhook/` address.

|  | Polling (default) | Webhook |
| --- | --- | --- |
| Who starts it | Jenkins asks every minute | GitHub calls Jenkins |
| Delay | Up to 1 minute | A few seconds |
| Needs | Nothing | Jenkins reachable from the internet |

Jenkins on `localhost:8080` can't be reached by GitHub, so locally you need **ngrok** to give it a public address. Keep polling on as a backup; companies do too, in case a webhook is missed.

**Option A: script**

1. In a second terminal: `ngrok http 8080` and copy the `https://xxxx.ngrok-free.app` URL.
2. Set it in `.env` as `JENKINS_URL=https://xxxx.ngrok-free.app/` and run `docker compose up -d`.
3. Create the webhook:

   ```bash
   ./scripts/create-webhook.sh Yaathvik2020 demo-app https://xxxx.ngrok-free.app
   ```

Or do steps 2–3 plus branch protection in one go: `./setup.sh https://xxxx.ngrok-free.app` (`./setup.sh` alone skips the webhook).

**Option B: GitHub UI**

1. Repo → **Settings → Webhooks → Add webhook**.
2. Payload URL: `https://xxxx.ngrok-free.app/github-webhook/` (the trailing `/github-webhook/` is required).
3. Content type: `application/json`.
4. Events: **Let me select individual events** → tick **Pushes** and **Pull requests**.
5. Active → **Add webhook**.

**Check it works:** Settings → Webhooks → your webhook → **Recent Deliveries** shows a green tick. Push a commit: the build starts within seconds.

**Note:** free ngrok gives a new URL each restart. Update `JENKINS_URL` and the webhook (or delete the old one and rerun the script) every time it changes.

## 6. Daily work: feature → develop

A developer branches from develop, opens a PR back into develop, and merges only when Jenkins is green; the merge deploys to DEV.

&#91;embedded content: daily PR loop · fix and push until green\]

A red PR is fixed on the same branch; branch protection keeps the Merge button blocked until the check is green.

1. **Start from the latest develop**

   ```bash
   git checkout develop
   git pull origin develop
   git checkout -b feature/add-subtract
   ```
2. **Change code, commit, push**

   ```bash
   git add .
   git commit -m "Add subtract method"
   git push -u origin feature/add-subtract
   ```

   Jenkins builds the branch (check `jenkins/branch`).
3. **Open the PR.** GitHub → **Compare & pull request**, with **base: develop ← compare: feature/add-subtract**. Give a clear title; it becomes `CHANGE_TITLE`. Or: `gh pr create --base develop --title "Add subtract method" --body "New feature"`.
4. **Jenkins checks the PR.** It creates the PR job (shown as *Add subtract method (#1)*, real name **PR-1**) and tests the code merged with develop (check `jenkins/pr-merge`). No Docker, no deploy. The feature branch job is crossed out because the PR job now builds it.
5. **If it fails (red X):** open the PR job → build → **Console Output**, read the first ERROR line, fix on the same branch and push again. Do not open a new PR; the same PR rebuilds as build #2.

   ```bash
   git checkout feature/add-subtract
   git add . && git commit -m "Fix test" && git push
   ```
6. **When green: merge.** Merge pull request → Confirm merge → **Delete branch**.
7. **develop deploys DEV.** Jenkins builds develop: Build & Test → Docker Build & Push → Deploy to DEV.

   ```bash
   curl http://localhost:8081      # Hello from demo-app! Environment: DEV
   ```
8. **Clean up locally**

   ```bash
   git checkout develop && git pull
   git branch -d feature/add-subtract
   ```

## 7. Release: develop → main → PROD

When DEV is tested, a release PR moves develop into main; the main build waits until `release-manager` approves, then deploys PROD.

1. **Open the release PR.** GitHub → Pull requests → New pull request with **base: main ← compare: develop** (base = where code is going). If GitHub says *There isn't anything to compare*, the two are swapped; click the arrows. Or: `gh pr create --base main --head develop --title "Release v1.1" --body "Release to PROD"`.
2. **Jenkins checks it** (build + test only, no deploy). Wait for the green tick.
3. **Merge it. Never delete develop**; it's permanent.
4. **main builds and pauses.** Build & Test → Docker Build & Push → **Approval for PROD**. GitHub shows an orange dot while it waits. Ignore the yellow *main had recent pushes – Compare & pull request* banner; it's only a suggestion.
5. **Approve.** Log out, log in as `release-manager`, open the main build, click **Deploy** (on *Paused for Input* or the paused stage). `admin` is not allowed to approve.
6. **Check PROD**

   ```bash
   curl http://localhost:8082      # Hello from demo-app! Environment: PROD
   ```
7. **Sync main back to develop.** The merge adds a small merge commit to main, so GitHub says *main is 1 commit ahead of develop*. Open a PR **base: develop ← compare: main**, wait for green, merge. Both branches now match.

   ```bash
   gh pr create --base develop --head main --title "Sync main back to develop" --body "Back-merge after release"
   ```

## 8. Reference

**What runs when**

| Event | Build & Test | Scan | Docker image | Deploy |
| --- | --- | --- | --- | --- |
| Push to a feature branch (no PR yet) | Yes | Yes | No | No |
| PR into develop or main | Yes | Yes | No | No |
| Merge into develop | Yes | Yes | Yes | DEV (8081) |
| Merge into main | Yes | Yes | Yes | Approval → PROD (8082) |

**PR names.** GitHub numbers each PR (#3); Jenkins names the job `PR-` + number (**PR-3**) and displays the PR title, e.g. *Add multiply test (#3)*. See the real name in the URL (`.../job/PR-3/`) or by hovering. Issues and PRs share numbers, so gaps are normal.

**PR variables inside the pipeline**

| Variable | Example |
| --- | --- |
| `BRANCH_NAME` | PR-3 |
| `CHANGE_ID` | 3 |
| `CHANGE_TITLE` | Add multiply test |
| `CHANGE_BRANCH` | feature/add |
| `CHANGE_TARGET` | develop |
| `CHANGE_AUTHOR` | Yaathvik2020 |

Full list: `http://localhost:8080/env-vars.html`. `changeRequest()` is true when `CHANGE_ID` exists, which is how deploy stages skip PRs.

**Adding a new branch**

- **Just to test code** (e.g. `test/my-idea`): push it. Jenkins finds it and runs build + test. No file changes.
- **A new environment** (e.g. `test-env` deploying to QA): edit the library's `vars/javaPipeline.groovy` — add the branch to the Docker stage's `anyOf { ... }` and add a `Deploy to QA` stage with `branch 'test-env'`. Branch names are case-sensitive. Test the change on a library branch first with `@Library('company-pipeline@add-qa')`, then merge it. Add the branch to `branch-protection.sh`.
- **Never needed:** changes to `multibranch-job.groovy`, `jenkins.yaml` or plugins.

**Useful Git commands**

| Command | Shows / does |
| --- | --- |
| `git branch` | Branches on your PC |
| `git branch -a` | PC + GitHub branches |
| `git fetch origin` | Updates the list from GitHub |
| `git checkout <name>` | Switches; creates a local copy of a GitHub branch |

## 9. Troubleshooting

These are the problems hit while building this setup, with their fixes.

| Problem | Cause | Fix |
| --- | --- | --- |
| `git push`: *Repository not found* | Repo not created on GitHub, wrong name, or another account's saved login | Create the repo first (`gh repo create demo-app --public --source=. --remote=origin --push`), check the name, or clear `git:https://github.com` in Windows Credential Manager |
| `docker build`: *timestamps.hpi 404* | Wrong plugin ID | In `plugins.txt` use `timestamper`, then `docker compose build --pull --no-cache` |
| Stuck on *Jenkins is getting ready to work* | Config failed at startup | `docker compose logs jenkins` and look for the error |
| `ConfigurationAsCodeBootFailure` with `jobs: Failed to execute script` | Error in `multibranch-job.groovy` (e.g. `repositoryUrl()` in the old `github { }` block) | Remove the unsupported line or use the `traits { }` version; `docker compose down -v && docker compose up -d` |
| `ConfigurationAsCodeBootFailure` with a variable error | A `.env` value missing | Fill all 7 lines of `.env` |
| `$'\r': command not found` | Windows line endings | `sed -i 's/\r$//' <file>` and `git config --global core.autocrlf input` |
| *LF will be replaced by CRLF* | Warning only | `git config --global core.autocrlf input` |
| develop doesn't build | Branch not on GitHub, no Jenkinsfile, or main's approval holding the executor | **Scan Repository Now** + **Scan Repository Log**; abort or approve the paused main build |
| PR check never appears | No scan yet | Wait 1 minute or **Scan Repository Now** |
| *There isn't anything to compare* | base and compare swapped | base = target (main), compare = source (develop) |
| `git branch` doesn't list a branch | It exists only on GitHub | `git fetch origin`, then `git checkout <name>` |
| PR failed (red X) | Test or compile error | Console Output → fix on same branch → push |
| Token shown in chat or a screenshot | Leaked secret | Delete it in GitHub → Developer settings, make a new one, update `.env` |

**Clean up everything**

```bash
cd jenkins-platform
docker compose down -v
docker rm -f demo-app-dev demo-app-prod
```
