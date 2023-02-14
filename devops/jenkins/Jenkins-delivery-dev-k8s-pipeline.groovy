@Library('jenkins-sharedlib@feature/jenkins-ci')
import sharedlib.JenkinsfileUtil

def utils = new JenkinsfileUtil(steps, this)
/* Project settings */
def project = "demoncnp"
// Namespace settings
def namespace = project + "-dev"
// Environment settings
def deploymentEnvironment = "dev"
// aks | eks
def hosted = "aks"
// MAVEN339_JDK11_OPENJ9 | MAVEN339_JAVA8
def javaVersion = "MAVEN339_JDK11_OPENJ9"
// Cluster Name to deploy
def aksClusterName = "akspocncnpdev"
try {
  node {
    stage('Preparation') {
      cleanWs()
      checkout scm
      utils.prepare()
    }

    stage('Test') {
      utils.TestMaven()
    }

    stage('SonarQube') {
      utils.SonarQube()
    }

    stage('Build') {
      utils.BuildMaven()
    }

    stage('Package') {
      utils.PackageMaven()
    }

    def gitrepo = "${utils.currentGitURL}"
    def repobranch = "${utils.branchName}"
    def gitcommit = "${utils.currentGitCommit}"

    def group_id = utils.getElementContentFromPom("project.groupId")
    def pom_ap_name = utils.getElementContentFromPom("project.artifactId")
    def ap_version = utils.getElementContentFromPom("project.version")
    def parent_version = utils.getElementContentFromPom("project.parent.artifactId")
    def parent_name = utils.getElementContentFromPom("project.parent.version")
    def job_url = env.JOB_NAME + '/' + env.BUILD_ID
    def build_user_id = ""
    def build_user = ""

    def deliverybranchname = "master"
    utils.downloadGitRepo("35449215", deliverybranchname, "gitlab-token-delivery", "branch")
    sh "cp -r tmp-repository/35449215/devops/ansible devops"
    sh "cp -r tmp-repository/35449215/devops/docker devops"
    sh "cp -r tmp-repository/35449215/devops/kustomize devops"

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

        if ( hosted == 'aks' ) {
          ansible_cmd_extra << [acr_build_sp: "${ACRBUILD_SP}",
                                acr_build_tenant: "${ACRBUILD_TENANT}",
                                acr_build_secret: "${ACRBUILD_SECRET}",
                                acr_build_subscription: "${ACRBUILD_SUBSCRIPTION}"]
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
