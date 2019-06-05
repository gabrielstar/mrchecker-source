class JobConfig {
    def URL
    def oldItemsNumKeep = '3'
    def oldItemsDaysKeep = '1'
    protected String jobName
    protected String scriptPath
    protected String credentialsId
    def performanceJobs = []

    String toString() {
        return "$URL:$oldItemsNumKeep:$oldItemsDaysKeep"
    }
}

class PerformanceJobConfig extends JobConfig{

}

String folderSource = '''
folder(':folder:') {
    description('Repository jobs for Zensus Projects')
}

'''
String dslScriptTemplate = '''
multibranchPipelineJob(':folder:/:jobName:.:browser:.:env:') {
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

String dslTestPipelineTemplate = '''
    pipelineJob(':folder:/:jobName:.:env:'){
      description(":description:")
      definition{
        cpsFlowDefinition{
          script("""
                    def buildJob
                    def failureStatus = 'FAILURE'
                    node(){
                        :jobList:
                    }
                 """)
          sandbox(true)
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
    FEATURE("feature"), REGRESSION("regression"), STANDALONE("standalone"), PIPELINE("pipeline"),
    PERFORMANCE("performance"),PERFORMANCE_REGRESSION("performance/regression"), PERFORMANCE_FEATURE("performance/feature")
    String folder
    String rootFolder = "tests"

    private JOB_TYPES(String folder) {
        this.folder = "$rootFolder/$folder"
    }
}

//envs and envs templates for where they are dynamically generated
public enum ENVIRONMENTS {
    DEV("DEV__ENV__01"), DEV2("DEV__ENV__02"),PINT1("PINT1"), PINT2("PINT2")
    final String env

    private ENVIRONMENTS(String env) {
        this.env = env
    }
}
final String mainFolder = "tests"
final List browsers = ["firefox", "ie"]
List pipelineBrowsers = browsers
pipelineBrowsers.removeAll(["ie"]) //jobs for this browser will not make part of test pipeline
//we cannot use "-" here because static methods are disallowed on jenkins

//generations exclusions
def excludedEnvironments = []
def excludedEnvironmentsForRegression = []
//some projects use common envs e.g. datenportal-gateway -> datenportal
def replaceMapEnvs = ["-gateway_":""]
List dslScripts = []
def credentialsId = 'CORP-TU'

def getRegressionJobFor(String projectName,String browser,String env,String branch, boolean isPerformance) {
    def jobRelativePath
    def jobRoot
    def downstreamJob
    def jobDescription
    if(isPerformance){
        jobRoot = "tests/performance/regression/"
        jobDescription = "Performance tests for $projectName"
        jobRelativePath = "${projectName.toLowerCase()}.${replaceVariablesForEnvironments(env,projectName)}/$branch"

    }else{
        jobRoot = "tests/regression/"
        jobDescription = "Functional Tests Regression with $browser for $projectName"
        jobRelativePath = "${projectName.toLowerCase()}.$browser.${replaceVariablesForEnvironments(env,projectName)}/$branch"
    }

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


def getJobForConfig(String jobTemplate, JobConfig jobConfig, JOB_TYPES jobType, String description, String browser, String env) {
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
            replaceAll(':browser:', browser).
            replaceAll(':env:', env)
}

String replaceVariablesForEnvironments(String env, String projectKey){
    return env.replace("_ENV_", projectKey)
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
def generateFeatureJobConfigs(String repoName, JobConfig repoConfig, def dslScriptTemplate, def browsers){
    List<JobConfig> configs = []
    browsers.each { browser ->
        def description = "This is the feature job for project ${repoName} for browser ${browser}. By default it runs all tests that are tagged with branch name e.g. @SAF-203. All feature branches get their own jobs. They need to be triggered manually."
        configs.add(
                getJobForConfig(dslScriptTemplate, repoConfig, JOB_TYPES.FEATURE, description, browser, "")
        )
    }
    configs
}

def generateRegressionJobConfigs(String repoName, JobConfig repoConfig, def dslScriptTemplate, def browsers, def ENVIRONMENTS, def excludedEnvironmentsForRegression){
    def configs = []
    browsers.each { browser ->
        ENVIRONMENTS.each {
            //regression jobs for develop, for each browser and environment, every 60 mins
            description = "This is the regression job for project ${repoName} for browser ${browser} and environment ${it.env}. By default it runs all tests that are tagged with @regression tag. Only develop gets regression job by default. "
            description += "They run regularly twice a day with cron job."
            def env = replaceVariablesForEnvironments(it.env, repoConfig["jobName"])
            if(!(env in excludedEnvironmentsForRegression)) {
                println "ENV: $env"
                configs.add(getJobForConfig(dslScriptTemplate, repoConfig, JOB_TYPES.REGRESSION, description, browser, env))
            }
        }
    }
    configs
}

def generateTestPipelinesJobConfigs(String repoName, JobConfig repoConfig, def dslTestPipelineTemplate, def dslPerformanceTemplate,  def browsers, def ENVIRONMENTS, def excludedEnvironmentsForRegression){
    def configs = []
    ENVIRONMENTS.each {
        description = "Test Pipelines for integration with Production Line"
        def env = replaceVariablesForEnvironments(it.env, repoConfig["jobName"])
        if(!(env in excludedEnvironmentsForRegression)) {
            configs.add(
                    getJobForConfig(dslTestPipelineTemplate, repoConfig, JOB_TYPES.PIPELINE, description, "", env)
                            .replaceAll(":jobList:",
                            browsers.collect { browser ->
                                getRegressionJobFor(repoConfig['jobName'], browser, it.env, "develop", false)
                            }
                            .join("\n ")
                                    .plus("\n")
                                    .plus(
                                    repoConfig.performanceJobs.collect { def performanceRepoConfig->
                                        getRegressionJobFor(performanceRepoConfig['jobName'], "", env, "develop", true)
                                    }.join("\n")
                            )
                    )
            )
        }
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
            dslScripts += generateFeatureJobConfigs(repoName, repoConfig, dslScriptTemplate,browsers)

            println "Generating functional tests regression jobs configs: "
            dslScripts += generateRegressionJobConfigs(repoName,repoConfig,dslScriptTemplate,browsers,ENVIRONMENTS, excludedEnvironmentsForRegression)

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
