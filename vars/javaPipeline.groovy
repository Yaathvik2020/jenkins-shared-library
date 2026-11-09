// Standard pipeline for every Java (Maven) service in the company.
// Used from an app's Jenkinsfile:   javaPipeline(appName: 'demo-app', devPort: 8081, prodPort: 8082)
//
// Branch strategy (2 long-lived branches):
//   feature/*  -> PR into develop  : build + test only (no deploy)
//   develop    -> after merge      : build + test + image + deploy DEV
//   develop -> PR into main        : build + test only (no deploy)
//   main       -> after merge      : build + test + image + approval + deploy PROD

def call(Map cfg = [:]) {
  if (!cfg.appName) { error 'javaPipeline: appName is required' }
  def registry  = cfg.registry  ?: 'localhost:5000'
  def approvers = cfg.approvers ?: 'release-manager'

  pipeline {
    agent any

    options {
      timeout(time: 30, unit: 'MINUTES')
      buildDiscarder(logRotator(numToKeepStr: '10'))
      disableConcurrentBuilds(abortPrevious: true)
      timestamps()
    }

    environment {
      IMAGE_REPO = "${registry}/${cfg.appName}"
    }

    stages {

      stage('Build Info') {
        steps {
          script {
            env.GIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            env.IMAGE_TAG = "${env.GIT_SHORT}-${env.BUILD_NUMBER}"
            if (env.CHANGE_ID) {
              echo "PULL REQUEST #${env.CHANGE_ID}: '${env.CHANGE_BRANCH}' -> '${env.CHANGE_TARGET}' by ${env.CHANGE_AUTHOR}"
            } else {
              echo "BRANCH BUILD: ${env.BRANCH_NAME} (commit ${env.GIT_SHORT})"
            }
          }
        }
      }

      stage('Build & Unit Test') {
        steps { sh 'mvn -B clean verify' }
        post {
          always { junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml' }
        }
      }

      // In a company this is SonarQube / Snyk / Trivy. Kept as a placeholder so the demo runs anywhere.
      stage('Code Quality & Security Scan') {
        steps { echo 'Placeholder: run SonarQube + dependency/image scan here' }
      }

      // ---------- Everything below runs only AFTER a merge, never for a PR ----------

      stage('Docker Build & Push') {
        when { allOf { not { changeRequest() }; anyOf { branch 'develop'; branch 'main' } } }
        steps {
          sh '''
            docker build -t ${IMAGE_REPO}:${IMAGE_TAG} .
            docker push ${IMAGE_REPO}:${IMAGE_TAG}
          '''
          echo "Pushed ${env.IMAGE_REPO}:${env.IMAGE_TAG}"
        }
      }

      stage('Deploy to DEV') {
        when { allOf { not { changeRequest() }; branch 'develop' } }
        steps {
          deployContainer(name: "${cfg.appName}-dev", image: "${env.IMAGE_REPO}:${env.IMAGE_TAG}",
                          port: cfg.devPort ?: 8081, envName: 'DEV')
        }
      }
      stage('Deploy to QA') {
        when { allOf { not { changeRequest() }; branch 'Test-Env' } }
        steps {
          deployContainer(name: "${cfg.appName}-qa", image: "${env.IMAGE_REPO}:${env.IMAGE_TAG}",
                          port: cfg.qaPort ?: 8083, envName: 'QA')
        }
      }
      stage('Approval for PROD') {
        when { allOf { not { changeRequest() }; branch 'main' } }
        steps {
          timeout(time: 1, unit: 'HOURS') {
            input message: "Deploy ${cfg.appName}:${env.IMAGE_TAG} to PRODUCTION?",
                  ok: 'Deploy',
                  submitter: approvers   // only these users can approve
          }
        }
      }

      stage('Deploy to PROD') {
        when { allOf { not { changeRequest() }; branch 'main' } }
        steps {
          deployContainer(name: "${cfg.appName}-prod", image: "${env.IMAGE_REPO}:${env.IMAGE_TAG}",
                          port: cfg.prodPort ?: 8082, envName: 'PROD')
        }
      }
    }

    post {
      success { echo "SUCCESS: ${env.BRANCH_NAME}" }
      failure { echo "FAILED: ${env.BRANCH_NAME}  (in a company: Slack/email alert goes here)" }
      always  { cleanWs() }
    }
  }
}
