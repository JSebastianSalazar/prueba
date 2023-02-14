@Library('jenkins-sharedlib@feature/jenkins-ci')
import sharedlib.JenkinsfileUtil

def utils = new JenkinsfileUtil(steps, this)
/* Project settings */
def project = "demoalcatraz"
// Namespace settings
def namespace = "appsprod"
// Environment settings
def deploymentEnvironment = "prod"
// aks | eks
def hosted = "eks"
// MAVEN339_JDK11_OPENJ9 | MAVEN339_JAVA8
def javaVersion = "MAVEN339_JDK11_OPENJ9"
// Cluster Name to deploy
def aksClusterName = "AKSPOCCLIENTPROD"
try {
  node {
    stage('Preparation') {
      cleanWs()
      checkout scm
      utils.prepare()
    }

    stage('Release') {
      env.releaseCandidate = params.RELEASE_TAG_NAME
      def ap_version = utils.getElementContentFromPom("project.version")

      withCredentials([
        [$class: 'UsernamePasswordMultiBinding', credentialsId: "${project}-gitops-token-${deploymentEnvironment}", usernameVariable: 'PROJECT_GITOPS_USER', passwordVariable: 'PROJECT_GITOPS_TOKEN']
      ]) {
        script {
                        env.encodedUser=URLEncoder.encode(PROJECT_GITOPS_USER, "UTF-8")
                        env.encodedPass=URLEncoder.encode(PROJECT_GITOPS_TOKEN, "UTF-8")
                        env.ap_version="${ap_version}"
                    }
          repo = sh(script: """gitRepositoryUrl=\$(git config remote.origin.url)
          hostname=https://gitlab.com/
          hostnameIndexLenght=\$(echo \$(expr length \${hostname}))
          gitRepositoryUrlLenght=\$(echo \$(expr length \${gitRepositoryUrl}))
          gitRepositoryPath=\$(echo \${gitRepositoryUrl} | sed "s|\${hostname}||g")
          echo \${gitRepositoryPath}
          """, returnStdout: true)
          env.trimRepoPath = repo.trim()
          sh '''
            version=\$ap_version
            timestamp=\$(date +%Y%m%d%H%M%S)
            msgheader=\"[ci-release/\$version] Merge-overwrite-release-tag-into-master: \$version\"
            msgsource=\"branch-source: release/\$version\"
            msgcommit=\"commit-source: \$(git log --format="%H" -n 1)\"
            msgdate=\"date: \$timestamp\"
            msgbuild=\"build-url: $JOB_URL\"
            msguser=\"build-user: devops\"
            comment="\$msgheader\n\$msgsource\n\$msgcommit\n\$msgdate\n\$msgbuild\n\$msguser"

            git config user.email \"devops@ic.com\"
            git config user.name \"devops\"

            tagExistQuery=\$(git tag --list \$version)
            if [ \"\${tagExistQuery}\" ]
            then
              echo "***********************************************************"
              echo "Release tag already exists, please validate pom.xml version"
              echo "***********************************************************"
              exit 1
            fi

            git branch --list
            git checkout -b master origin/master
            git checkout -b temp-$releaseCandidate refs/tags/$releaseCandidate

            git merge -v -s ours master -m \"\${comment}\"
            git status -v
            git status -v -sb

            git checkout master
            git merge -v --no-ff -X theirs temp-$releaseCandidate -m \"\${comment}\"
            git status -v
            git status -v -sb
            set +x
            git tag -a \$version -m \"Testing Start Release\"
            git push -q https://\$encodedUser:\$encodedPass@gitlab.com/\$trimRepoPath master
            git push -q https://\$encodedUser:\$encodedPass@gitlab.com/\$trimRepoPath \$version
            echo "$releaseCandidate Merged to Master and created tag: \$version"
            '''
      }
    }

    stage('SaveResults') {
      utils.SaveResults(deploymentEnvironment)
    }

    def gitrepo = "${utils.currentGitURL}"
    def repobranch = "${utils.branchName}"
    def gitcommit = "${utils.currentGitCommit}"

    def group_id = utils.getElementContentFromPom("project.groupId")
    def pom_ap_name = utils.getElementContentFromPom("project.artifactId")
    def ap_version = utils.getElementContentFromPom("project.version")
    def parent_version = ""
    def parent_name = ""
    def job_url = ""
    def build_user_id = ""
    def build_user = ""

    def deliverybranchname = "1.1.0"
    utils.downloadGitRepo("24432180", deliverybranchname, "gitlab-token-delivery", "tag")
    sh "cp -r tmp-repository/24432180/devops/ansible devops"
    sh "cp -r tmp-repository/24432180/devops/docker devops"
    sh "cp -r tmp-repository/24432180/devops/kustomize devops"

    sh "sed -i 's/\${deploymentEnvironment}/${deploymentEnvironment}/g' ${WORKSPACE}/devops/jenkins/credentials-map.yaml"
    sh "sed -i 's/\${project}/"+"${project}".toLowerCase()+"/g' ${WORKSPACE}/devops/jenkins/credentials-map.yaml"

    def hosted_on_environment = readYaml file: "${WORKSPACE}/devops/jenkins/credentials-map.yaml"
    credentialBinding = utils.getCredentialMap(hosted_on_environment[hosted].credentials)

    wrap([$class: 'BuildUser']) { build_user_id = "${BUILD_USER_ID}"; build_user = "${BUILD_USER}".replace(' ','_') }

    def ansible_cmd_common = [
      workspace: "${WORKSPACE}", hosted_on: hosted, deployment_environment: deploymentEnvironment,
      pom_ap_name: pom_ap_name, ap_version: ap_version, parent_version: parent_version,
      group_id: group_id, namespace: "${namespace}".toLowerCase(), project: "${project}".toLowerCase(),
      git_repo: gitrepo, repo_branch: repobranch, git_commit: "${gitcommit}", delivery_branch: "${deliverybranchname}",
      cluster_name: "${aksClusterName}".toLowerCase(), java_version: javaVersion, parent_name: parent_name
    ]

    stage('Build Docker') {
      def ansible_cmd = "ansible-playbook -v devops/ansible/site-build-docker.yml -i devops/ansible/hosts.yml \
                         -e @${WORKSPACE}/devops/ansible/roles/vars/${deploymentEnvironment}-vars.yaml "
      for (item in ansible_cmd_common) {
        ansible_cmd+='-e '+item.key+'='+item.value+' '
      }

      withCredentials(credentialBinding){
        def ansible_cmd_extra = [:]

        ansible_cmd_extra << [nexus_username: "${NEXUS_USERNAME}",
                              nexus_password: "${NEXUS_PASSWORD}"]

        if ( hosted == 'aks' ) {
          ansible_cmd_extra << [acr_build_sp: "${ACRBUILD_SP}",
                                acr_build_tenant: "${ACRBUILD_TENANT}",
                                acr_build_secret: "${ACRBUILD_SECRET}"]
        }
        if ( hosted == 'eks' ) {
          ansible_cmd_extra << [aws_region: "${ECR_REGION}",
                                ecr_aws_access_key_id: "${ECR_AWS_ACCESS_KEY_ID}",
                                ecr_aws_secret_access_key: "${ECR_AWS_SECRET_ACCESS_KEY}"]
        }

        for (item in ansible_cmd_extra) {
          ansible_cmd+="-e "+item.key+'='+item.value+' '
        }

        sh "${ansible_cmd}"
      }
    }

    stage('Delivery app to ' + deploymentEnvironment) {
      def ansible_cmd = "ansible-playbook -v devops/ansible/site-metadata-deploy.yml -i devops/ansible/hosts.yml \
                         -e @${WORKSPACE}/devops/ansible/roles/vars/${deploymentEnvironment}-vars.yaml \
                         -e @${WORKSPACE}/devops/deploy/${deploymentEnvironment}-vars.yaml "
      for (item in ansible_cmd_common) {
        ansible_cmd+='-e '+item.key+'='+item.value+' '
      }

      withCredentials(credentialBinding){
        def ansible_cmd_extra = [:]

        ansible_cmd_extra << [build_user: build_user,
                              build_user_id: build_user_id,
                              job_url: job_url,
                              project_gitops_user: PROJECT_GITOPS_USER,
                              project_gitops_token: PROJECT_GITOPS_TOKEN]

        for (item in ansible_cmd_extra) {
          ansible_cmd+="-e "+item.key+'='+item.value+' '
        }

        sh "${ansible_cmd}"
      }
    }

  }
} catch (Exception e) {
  node {
    throw e
  }
}