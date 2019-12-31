@NonCPS
def getProjects(json) {
	def items = new groovy.json.JsonSlurper().parseText(json).items
	def projects = []
	for (int i = 0; i < items.size(); i++) {
		projects << items[i]['metadata']['name']
	}
	return projects.sort()
}

@NonCPS
def getDataBaseExternalName(json) {
	return new groovy.json.JsonSlurper().parseText(json).spec.externalName
}

def getNextProjectName() {
	sh "oc get projects --selector type=dq -o json > projects.json"
	def json = readFile('projects.json')	
	def projects = getProjects(json)
	if (projects.isEmpty()) {
		return "dq00"
	} else {
		def matcher = projects.last() =~ /(dq)(\d+\d+)/
		def env = "${matcher[0][2].toInteger()+1}"
		return "${matcher[0][1]}${env.padLeft(2, '0')}"	
	}
}

def getDatabaseEndPoint() {
	sh "oc get service postgresql -o json -n ${params.PRODUCT}-build > db.json"
	def db = readFile('db.json')	
	return getDataBaseExternalName(db)
}

node {
	
	def project
	
	properties([
	  parameters([
	     string(name: 'GITHUB'), 
	     string(name: 'REPO'), 
	     string(name: 'PROJECT_TITLE'), 
	     string(name: 'MASTER_HOST'), 
	     string(name: 'ADMIN_USER'), 
	     string(name: 'ADMIN_PASSWORD'),
	     string(name: 'PRODUCT'),
	  ])
	])
	
	stage("checkout project") {
		checkout scm: [$class: 'GitSCM', 
      userRemoteConfigs: [[url: "https://github.com/${params.GITHUB}/${params.REPO}"]], 
      branches: [[name: "refs/tags/${version}"]]], changelog: false, poll: false
	}	
	
	stage ("connect as admin") {
		sh "oc login --insecure-skip-tls-verify=true -u ${params.ADMIN_USER} -p ${params.ADMIN_PASSWORD} ${params.MASTER_HOST}"
	}
	
	stage ("create the namespace") {
		project = getNextProjectName()
		sh "oc new-project $project --display-name='${params.PROJECT_TITLE}'"
		sh "oc label namespace $project type=dq"
		sh "oc policy add-role-to-user edit system:serviceaccount:${params.PRODUCT}-cicd:jenkins -n $project"
	}
	
	stage ("create the message broker") {
		sh "oc process amq63-basic -n openshift -p IMAGE_STREAM_NAMESPACE=openshift -p MQ_USERNAME=amq -p MQ_PASSWORD=amq | oc create -f -"
		sh "oc set probe dc/broker-amq --readiness --remove"
		openshiftVerifyDeployment namespace: project, depCfg: "broker-amq", replicaCount:"1", verifyReplicaCount: "true", waitTime: "300000" 
	}
	
	stage ("create the jaeger server") {
		sh "oc process -f https://raw.githubusercontent.com/jaegertracing/jaeger-openshift/master/all-in-one/jaeger-all-in-one-template.yml | oc create -f -"
	}
	
	stage ("create the database endpoint") {
		def database = getDatabaseEndPoint()
		sh "oc process -f estafet-microservices-scrum/setup-environments/templates/database-service.yml -p DB_HOST=$database | oc apply -f -"
	}
	
	stage ('create each microservice') {
		def microservices = readYaml file: "${params.REPO}/setup-environments/vars/microservices-vars.yml"
		microservices.each { microservice ->
			openshiftBuild namespace: "${params.PRODUCT}-cicd", buildConfig: "${pipeline}", waitTime: "300000"
			openshiftVerifyBuild namespace: "${params.PRODUCT}-cicd", buildConfig: "${pipeline}", waitTime: "300000" 
		}		
	}
	
}
