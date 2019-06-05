class JobConfig {
    def URL
    def oldItemsNumKeep = '3'
    def oldItemsDaysKeep = '1'
    def jobName
    def scriptPath
    def credentialsId
}

String folderSource = '''
folder(':folder:') {
    description('Test Jobs for MrChecker')
}
'''

String dslScriptTemplate = '''
multibranchPipelineJob(':folder:/:jobName:') {
    description(":description:")
    displayName(":jobName:")
    branchSources{
           branchSource {
            source {
                git {
                  remote(':URL:')
                  credentialsId(':credentialsId:')
                  traits{
                      headWildcardFilter {
                            includes(':includes:')
                            excludes(':excludes:')
                        }
                  }
                }
            }    
          strategy {
                defaultBranchPropertyStrategy {
                  props {
                    noTriggerBranchProperty() //to prevent starting builds on branch discovery
                  }
                }
              }
        }    
        factory{
            workflowBranchProjectFactory {
             // Relative location within the checkout of your Pipeline script.
                scriptPath(":scriptPath:")
            }
        }
        orphanedItemStrategy {
            discardOldItems {
                numToKeep(:oldItemsNumKeep:)
                daysToKeep(:oldItemsNumKeep:)
            }
       }
    }
    triggers{
        :trigger:
    }
    configure { node ->
        def traits = node / sources / data / 'jenkins.branch.BranchSource' / source / traits 
        traits <<  'jenkins.plugins.git.traits.BranchDiscoveryTrait'()  //enable discovery of branches
      }
}
'''

String dslScriptPipelineTemplate = '''
pipelineJob(':folder:/:jobName:') {
    description(":description:")
    definition{
      cpsScm{
          scm{
              git{
                  branches("develop")
                  remote{
                      url(':URL:')
                      credentials(':credentialsId:')
                  }
              }
          }
          scriptPath(':scriptPath:')
      }
    }
}
'''


String view = '''
            listView('tests/:name:') {
            description('')
            filterBuildQueue()
            filterExecutors()
            jobs {
                regex(/.*:regex:.*/)
            }
            recurse(true)
            columns {
                status()
                weather()
                name()
                lastSuccess()
                lastFailure()
                lastDuration()
                buildButton()
            }
        }
        '''

//performance feature & tests put inside because number of jobs per view grew too large
public enum JOB_TYPES {
    FEATURE("feature"), REGRESSION("regression"), STANDALONE("standalone")
    String folder
    String rootFolder = "tests"
    private JOB_TYPES(String folder) {
        this.folder = "$rootFolder/$folder"
    }
}

//in case we ever add run-time tests
public enum ENVIRONMENTS {
    DEV("DEV")
    final String env

    private ENVIRONMENTS(String env) {
        this.env = env
    }
}
final String mainFolder = "tests"
List dslScripts = []
def credentialsId = 'CORP-TU'

def getRegressionJobFor(String projectName,String env,String branch) {
    def jobRelativePath
    def jobRoot
    def downstreamJob
    def jobDescription

    jobRoot = "tests/regression/"
    jobDescription = "Functional Tests Regression for $projectName"
    jobRelativePath = "${projectName.toLowerCase()}.${replaceVariablesForEnvironments(env,projectName)}/$branch"


    downstreamJob = "buildJob = build job: '$jobRoot$jobRelativePath',propagate:false"

    return """
        stage('$jobDescription') {
            try{
                $downstreamJob
                currentBuild.result = buildJob.getResult()
            }catch(Exception e){
                currentBuild.result = failureStatus
            }
            if(currentBuild.result.contains(failureStatus)){
                print "Stopping pipeline as test have failed"
                error("Tests failed")
            }
        }
    """
}


def getJobForConfig(String jobTemplate, JobConfig jobConfig, JOB_TYPES jobType, String description, String env) {
    jobConfig['oldItemsNumKeep'] = jobConfig['oldItemsNumKeep'] ?: 1
    jobConfig['oldItemsDaysKeep'] = jobConfig['oldItemsDaysKeep'] ?: 1

    def includes
    def excludes
    def trigger

    switch (jobType) {
        case JOB_TYPES.FEATURE:
            includes = "*"
            excludes = "master"
            trigger = "periodic(15)"
            break
        case JOB_TYPES.REGRESSION:
            includes = "*develop"
            excludes = "fakefoo" //otherwise trait will throw error
            trigger = "periodic(60)"
            break
    }

    return jobTemplate.
            replaceAll(':description:', description).
            replaceAll(':URL:', jobConfig['URL']).
            replaceAll(':oldItemsNumKeep:', jobConfig['oldItemsNumKeep']).
            replaceAll(':oldItemsDaysKeep:', jobConfig['oldItemsDaysKeep']).
            replaceAll(':jobName:', jobConfig['jobName'].toLowerCase()).
            replaceAll(':folder:', jobType.folder).
            replaceAll(':scriptPath:', jobConfig['scriptPath']).
            replaceAll(':credentialsId:', jobConfig['credentialsId']).
            replaceAll(':includes:', includes).
            replaceAll(':excludes:', excludes).
            replaceAll(':trigger:', trigger).
            replaceAll(':env:', env)
}


Map<String, JobConfig> repoJobConfigs = [:]

repoJobConfigs.put('Checker',
        new JobConfig(
                URL: 'https://github.com/gabrielstar/mrchecker-source.git',
                jobName: 'Checker',
                credentialsId: "",
                scriptPath: 'mrchecker-framework-modules/mrchecker-webapi-module/pipelines/CI/Jenkinsfile_node.groovy'
        )
)


//generates functional feature jobs for all branches, with default environment, testers can change
def generateFeatureJobConfigs(String repoName, JobConfig repoConfig, def dslScriptTemplate){
    List<JobConfig> configs = []

    def description = "This is the feature job for project ${repoName} for. By default it runs all tests that are tagged with branch name e.g. @SAF-203. All feature branches get their own jobs. They need to be triggered manually."
    configs.add(
            getJobForConfig(dslScriptTemplate, repoConfig, JOB_TYPES.FEATURE, description, "")
    )

    configs
}

def generateRegressionJobConfigs(String repoName, JobConfig repoConfig, def dslScriptTemplate, def ENVIRONMENTS){
    def configs = []

    ENVIRONMENTS.each { env->
        //regression jobs for develop, for each browser and environment, every 60 mins
        description = "This is the regression job for project ${repoName}  and environment ${it.env}. By default it runs all tests that are tagged with @regression tag. Only develop gets regression job by default. "
        description += "They run regularly twice a day with cron job."

        println "ENV: $env"
        configs.add(getJobForConfig(dslScriptTemplate, repoConfig, JOB_TYPES.REGRESSION, description, env))

    }

    configs
}

def generateStandaloneJobConfigs(String repoName, JobConfig repoConfig, def dslScriptPipelineTemplate){
    def configs = []
    def description = ""
    configs.add(
            dslScriptPipelineTemplate.
                    replaceAll(':folder:', JOB_TYPES.STANDALONE.folder).
                    replaceAll(':description:', description).
                    replaceAll(':URL:', repoConfig['URL']).
                    replaceAll(':jobName:', repoConfig['jobName']).
                    replaceAll(':scriptPath:', repoConfig['scriptPath']).
                    replaceAll(':credentialsId:', repoConfig['credentialsId'])
    )
    configs
}

node() {

    stage("Create Folder Structure") {
        String folderDsl
        List folders = []
        folders.add(folderSource.replaceAll(':folder:', "tests"))
        JOB_TYPES.each {
            folders.add(folderSource.replaceAll(':folder:', it.folder))
        }
        folderDsl = folders.join("\n")
        writeFile(file: 'folderStructure.groovy', text: folderDsl)
        jobDsl failOnMissingPlugin: true, unstableOnDeprecation: true, targets: 'folderStructure.groovy'

    }
    stage("Prepare Job Configurations") {
        repoJobConfigs.each { String repoName, JobConfig repoConfig ->
            println "Generating functional tests feature jobs configs: "
            //feature jobs for all branches, with default environment, testers can change
            dslScripts += generateFeatureJobConfigs(repoName, repoConfig, dslScriptTemplate)

            println "Generating functional tests regression jobs configs: "
            dslScripts += generateRegressionJobConfigs(repoName,repoConfig,dslScriptTemplate,ENVIRONMENTS)

            println "Generating standalone jobs configs"
            //stand-alone jobs
            dslScripts +=generateStandaloneJobConfigs(repoName, repoConfig, dslScriptPipelineTemplate)

        }

    }

    stage('Prepare custom Views') {
        println "Preparing custom views"
        //feature
        repoJobConfigs.each {
            name, content ->
                dslScripts.add(view.
                        replaceAll(':name:', "Feature").
                        replaceAll(':regex:', "feature")
                )
        }
        //regressions
        dslScripts.add(view.
                replaceAll(':name:', 'Regressions').
                replaceAll(':regex:', 'regression')
        )
        //standalone
        dslScripts.add(view.
                replaceAll(':name:', 'Standalone').
                replaceAll(':regex:', 'standalone')
        )
    }
    stage('Create Jobs & Views') {
        println "Creating jobs and views"
        if (dslScripts.size() > 0) {
            String dslOutput = dslScripts.join("\n")
            println "Script source to execute:"
            println dslOutput
            writeFile(file: 'dslOutput.groovy', text: dslOutput)
            jobDsl failOnMissingPlugin: true, unstableOnDeprecation: true, targets: 'dslOutput.groovy'
        }
    }


}
