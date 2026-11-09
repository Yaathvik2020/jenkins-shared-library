// Job DSL: creates the Multibranch Pipeline job for the app repo.
// In a company the same script loops over a list of repos (or a GitHub Organization Folder is used).
def owner = System.getenv('GITHUB_OWNER')
def repo  = System.getenv('GITHUB_REPO')

multibranchPipelineJob(repo) {
  displayName(repo)
  description('Branches: main + develop + feature/*. PRs are built merged with their target.')

  branchSources {
    github {
      id("${repo}-github")
      scanCredentialsId('github-creds')
      repoOwner(owner)
      repository(repo)

      buildOriginBranch(true)          // main, develop, feature/*
      buildOriginBranchWithPR(false)   // a feature branch with an open PR is built only as the PR
      buildOriginPRMerge(true)         // PR tested as "merged with target branch"
      buildOriginPRHead(false)
      buildForkPRMerge(false)
      buildForkPRHead(false)
    }
  }

  factory {
    workflowBranchProjectFactory {
      scriptPath('Jenkinsfile')
    }
  }

  triggers {
    periodicFolderTrigger { interval('1m') }   // backup if no webhook
  }

  orphanedItemStrategy {
    discardOldItems { daysToKeep(7); numToKeep(20) }
  }
}
