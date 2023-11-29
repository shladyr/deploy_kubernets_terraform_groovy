#!/usr/bin/env groovy

def call(String action,
         String k8sName,
         String deploymentName,
         String coreEnvName,
         String k8sEnvName,
         String k8sAwsRegion,
         String k8sClusterName,
         String terraformDir = "terraform",
         String inputBuildTag = "",
         String coreAwsRegion = "us-west-2",
         Boolean deploy_auth = false,
         String extraArgs = "") {

    // Core/Team vars
    def coreAwsProfile = [:]
    coreAwsProfile["stage"] = "project-stage"
    coreAwsProfile["prod"]  = "project-prod"
    def coreAwsAccountIds   = [:]
    coreAwsAccountIds["stage"]      = "************"
    coreAwsAccountIds["prod"]       = "************"

    // K8s vars
    def k8sAwsProfile = [:]
    def k8sAwsAccountIds = [:]

    if (k8sName == "ATLANTIDA") {
        k8sAwsProfile["dev"]        = "atl-application-dev"
        k8sAwsProfile["stage"]      = "atl-application-stage"
        k8sAwsProfile["pre-prod"]   = "atl-application-stage"
        k8sAwsProfile["prod"]       = "atl-application-prod"

        k8sAwsAccountIds["dev"]      = "************"
        k8sAwsAccountIds["stage"]    = "************"
        k8sAwsAccountIds["pre-prod"] = "************"
        k8sAwsAccountIds["prod"]     = "************"

    } else if (k8sName == "ARCTICA") {
        k8sAwsProfile["dev"]         = "arc-application-dev"
        k8sAwsProfile["stage"]       = "arc-application-stage"
        k8sAwsProfile["pre-prod"]    = "arc-application-stage"
        k8sAwsProfile["prod"]        = "arc-application-prod"

        k8sAwsAccountIds["dev"]      = "************"
        k8sAwsAccountIds["pre-prod"] = "************"
        k8sAwsAccountIds["stage"]    = "************"
        k8sAwsAccountIds["prod"]     = "************"
    }

    // Default action strings for "up"
    String actionKeyword = "Deploy EKS"
    // Deploy non-interactively.
    terragruntActions = "up"
    // Validate requested action is "up" or "down"
    if ((action != "up") && (action != "down")) {
        error "Invalid deployment action '${action}'"
    }
    // For "down" action. change commands
    if (action == "down") {
        actionKeyword = "Destroy"
        terragruntActions = "down"
    }
  
    stage("${actionKeyword} ${deploymentName} on ${k8sName}. Project ${coreEnvName} - ${coreAwsRegion}/${k8sName} ${k8sEnvName} - ${k8sAwsRegion}") {
        String buildTag = inputBuildTag == "" ? "${getImageTagEks(env.BRANCH_NAME)}" : inputBuildTag
        awsCreateSession(coreAwsAccountIds["prod"], "owner", coreAwsProfile["prod"])
        awsCreateSession(coreAwsAccountIds[coreEnvName], "owner", coreAwsProfile[coreEnvName])
        if (k8sName == "Avalon") {
            awsCreateSession(k8sAwsAccountIds[k8sEnvName], "team", k8sAwsProfile[k8sEnvName])
        } else {
            awsCreateSession(k8sAwsAccountIds[k8sEnvName], "team-sre", k8sAwsProfile[k8sEnvName])
        }
        sh "aws eks update-kubeconfig --region ${k8sAwsRegion} --name ${k8sClusterName} --profile ${k8sAwsProfile[k8sEnvName]}"
        withTerra(terraformVersion: "1.3.3", terragruntVersion: "0.35.4") {
            dir("${terraformDir}/") {
                // terragrunt init
                // AWS_PROFILE - we pull secrets from this profile
                sh "AWS_PROFILE=${coreAwsProfile["prod"]} CORE_AWS_PROFILE=${coreAwsProfile[coreEnvName]} K8S_AWS_PROFILE=${k8sAwsProfile[k8sEnvName]} BUILD_TAG=${buildTag} ${extraArgs} summon -f ${WORKSPACE}/secrets/${coreEnvName}.yml -p summon-aws-secrets terragrunt run-all init --terragrunt-non-interactive"

                if (terragruntActions == "up") {

                    // terragrunt plan
                    response = sh(script: "AWS_PROFILE=${coreAwsProfile["prod"]} CORE_AWS_PROFILE=${coreAwsProfile[coreEnvName]} K8S_AWS_PROFILE=${k8sAwsProfile[k8sEnvName]} BUILD_TAG=${buildTag} ${extraArgs} summon -f ${WORKSPACE}/secrets/${coreEnvName}.yml -p summon-aws-secrets terragrunt run-all plan --terragrunt-non-interactive | tee plan.out", returnStatus: true)
                    println(response)

                    // If deploy authorization is enabled, check if the resources are undergoing a redeploy from the plan output.
                    if (deploy_auth) {
                        String stringChecks = ""
                        // If there is a match on the below awk+grep command, the exit code will be 0, else a non-zero.
                        plan_out = sh(script: "awk \"/${stringChecks}/\" plan.out | grep replaced", returnStatus: true)
                        println(plan_out)
                        // If there is a match, ask for a user input to proceed.
                        if (plan_out == 0) {
                            def userInput = input(id: 'confirm', message: 'Authorization required!', parameters: [[$class: 'BooleanParameterDefinition', defaultValue: false, description: "It seems one of the following resources will be redeployed.\n${stringChecks} \nThis will require a downtime change notice. Please confirm if you want to continue.", name: 'Please confirm you agree with this']])
                        }
                    }

                    // terragrunt apply
                    sh "AWS_PROFILE=${coreAwsProfile["prod"]} CORE_AWS_PROFILE=${coreAwsProfile[coreEnvName]} K8S_AWS_PROFILE=${k8sAwsProfile[k8sEnvName]} BUILD_TAG=${buildTag} ${extraArgs} summon -f ${WORKSPACE}/secrets/${coreEnvName}.yml -p summon-aws-secrets terragrunt run-all apply --terragrunt-non-interactive -auto-approve"
                
                    // if there was anything to add/change/destroy then no need to restart pods
                    terraformChanges = sh(script: "grep -e 'to change' -e 'to add' -e 'to destroy' plan.out", returnStatus: true)
                    // terraformChanges == 0 if there were changes and terraformChanges == 1 if deployment was not updated via terraform
                    println(terraformChanges)
                    if ( terraformChanges == 1 ) { 
                        if (buildTag.contains("master")) {
                            sh "kubectl rollout restart deployment/${deploymentName} --namespace team && kubectl rollout status deployment/${deploymentName} --namespace team"
                        } else {
                            sh "kubectl rollout restart deployment/${deploymentName}-${buildTag} §--namespace team && kubectl rollout status deployment/${deploymentName}-${buildTag} --namespace team"
                        }
                    }
                }

                // terragrunt destroy
                if (terragruntActions == "down") {
                    sh "AWS_PROFILE=${coreAwsProfile["prod"]} CORE_AWS_PROFILE=${coreAwsProfile[coreEnvName]} K8S_AWS_PROFILE=${k8sAwsProfile[k8sEnvName]} BUILD_TAG=${buildTag} ${extraArgs} summon -f ${WORKSPACE}/secrets/${coreEnvName}.yml -p summon-aws-secrets terragrunt run-all destroy --terragrunt-non-interactive"
                }

                // remove .terragrunt-cache folders to clear space on node
                sh "find . -type d -name \".terragrunt-cache\" -prune -exec rm -rf {} \\;"
            }
        }
    }
}
