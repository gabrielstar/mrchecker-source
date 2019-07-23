// CONFIG START
@NonCPS
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

public enum JOB_TYPES {
    FEATURE("feature"), REGRESSION("regression"), STANDALONE("standalone")
    String folder
    String rootFolder
    private JOB_TYPES(String rootFolder='tests',String folder) {
        this.folder = "$rootFolder/$folder"
    }
}

//CONFIG END

//replaces jenkins template variables
def getJobForConfig(String jobTemplate, JobConfig jobConfig, JOB_TYPES jobType, def description) {
    jobConfig['oldItemsNumKeep'] = jobConfig['oldItemsNumKeep'] ?: 1
    jobConfig['oldItemsDaysKeep'] = jobConfig['oldItemsDaysKeep'] ?: 1
    def includes, excludes, trigger

    switch (jobType) {
        case JOB_TYPES.FEATURE:
            includes = "*"
            excludes = "master develop"
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
            replaceAll(':trigger:', trigger)
}


//generates functional feature jobs for all branches
def generateFeatureJobConfigs(String repoName, JobConfig repoConfig, String dslScriptTemplate){
    List<JobConfig> configs = []
    def description = "This is the feature job for project ${repoName} for. All feature branches get their own jobs. They need to be triggered manually."
    configs.add(
            getJobForConfig(dslScriptTemplate, repoConfig, JOB_TYPES.FEATURE, description)
    )
    configs
}

//generates regression jobs
def generateRegressionJobConfigs(String repoName, JobConfig repoConfig, def dslScriptTemplate){
    List<JobConfig> configs = []
    def description = "This is the regression job for project ${repoName} . By default it runs all tests that are tagged with @regression tag. Only develop gets regression job by default. "
    description += "They run regularly twice a day with cron job."
    configs.add(
            getJobForConfig(dslScriptTemplate, repoConfig, JOB_TYPES.REGRESSION, description)
    )
    configs
}

//generates standalone jobs
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

Map<String, JobConfig> repoJobConfigs = [:]
def credentialsId = ''
def enabledModules = ['mrchecker-webapi','mrchecker-selenium']

enabledModules.each{
    moduleName ->
        repoJobConfigs.put(moduleName,
                new JobConfig(
                        URL: 'https://github.com/gabrielstar/mrchecker-source.git',
                        jobName: moduleName,
                        credentialsId: credentialsId,
                        scriptPath: "mrchecker-framework-modules/${moduleName}-module/pipelines/CI/Jenkinsfile_node.groovy"
                )
        )
}

List dslScripts = []

//GENERATE JOBS
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
            println "Generating tests feature jobs configs: "
            //feature jobs for all branches
            dslScripts += generateFeatureJobConfigs(repoName, repoConfig, dslScriptTemplate)

            println "Generating tests regression jobs configs: "
            dslScripts += generateRegressionJobConfigs(repoName,repoConfig,dslScriptTemplate)

            println "Generating standalone jobs configs"
            //stand-alone jobs
            dslScripts +=generateStandaloneJobConfigs(repoName, repoConfig, dslScriptPipelineTemplate)

        }

    }

    stage('Prepare custom Views') {

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

        println "Preparing custom views"
        //feature
        repoJobConfigs.each {
            name, content ->
                dslScripts.add(view.
                        replaceAll(':name:', "feature").
                        replaceAll(':regex:', "feature.*checker.+feature.+")
                )
        }
        //regressions
        dslScripts.add(view.
                replaceAll(':name:', 'regression').
                replaceAll(':regex:', 'regression.*checker.+develop')
        )
        //standalone
        dslScripts.add(view.
                replaceAll(':name:', 'standalone').
                replaceAll(':regex:', 'standalone.+')
        )
        //per module view
        enabledModules.each{
            moduleName ->
                dslScripts.add(view.
                        replaceAll(':name:',"m." + moduleName.split("-").last()).
                        replaceAll(':regex:', "${moduleName}.+")
                )
        }
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
