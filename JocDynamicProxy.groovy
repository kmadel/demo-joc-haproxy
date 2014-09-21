@Grab('commons-io:commons-io:2.4')

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.NameFileFilter
import org.apache.commons.io.filefilter.TrueFileFilter
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

NEWLINE = System.getProperty("line.separator")
JENKINS_OC_HOME = "/mnt/var/lib/jenkins-oc"
HA_PROXY_CFG = "/etc/haproxy/haproxy.cfg"
OLD_JENKINS_INSTANCES = [] as Set

run(args)

def run(args){
	def cli = new CliBuilder(usage: 'jocproxy.groovy -[hnd] [servers]')
	cli.with {
		h longOpt: 'help', 'Show usage information'
        n longOpt: 'name', args: 1, argName: 'name', 'Name of JOC server (httpPrefix)'
        d longOpt: 'delay', args: 1, argName: 'delay', 'Delay between polling interval in ms'
    }
    def options = cli.parse(args)

    if (!options) {
        return
    }
    if (options.h) {
        cli.usage()
        return
    }

	JenkinsInstance joc = new JenkinsInstance(name: options.n, jenkinsHome: JENKINS_OC_HOME, servers: options.arguments(), httpPort: 80)
	while(true){
		monitor(joc)
		Thread.sleep(options.d ? options.d as int : 5000)
		// new Timer().schedule({ monitor(joc) } as TimerTask, 1000, 5000)
	}
}

def monitor(JenkinsInstance joc){
	println("JocDynamicProxy: polling")

	// grab all connected client masters from joc
	File jocJobs = new File("${joc.jenkinsHome}/jobs")
	FileFilter configFilter = new NameFileFilter("config.xml")
	Collection<File> configs = FileUtils.listFiles(jocJobs, configFilter, TrueFileFilter.INSTANCE)

	//create a JenkinsIntance for each
	def jenkinsInstances = [joc] as Set
	configs.each{ file ->
        def config = new XmlParser().parse(file)
        if (config.name() == 'com.cloudbees.opscenter.server.model.ClientMaster'){
        	def clientMaster = new JenkinsInstance(name: config.encodedName.text(), servers: config.description.text().tokenize())
            jenkinsInstances.add(clientMaster)
        }
	}

	//dont reload unless there are new masters
	if(!OLD_JENKINS_INSTANCES.equals(jenkinsInstances)){
		println "JocDynamicProxy: detected jenkins instance change"
		// update haproxy.cfg
		updateAndReload(jenkinsInstances)

		// now determine jnlp ports
		jenkinsInstances.each{
			def port = getCliPort("http://proxy.jenkins-haproxy.dev.beedemo.io/" + it.name + "/") //TODO localhost?
			it.jnlpPort = port
		}

		updateAndReload(jenkinsInstances)
	}
	OLD_JENKINS_INSTANCES = jenkinsInstances
}

def updateAndReload(jenkinsInstances){
	File newCfg = new File("${HA_PROXY_CFG}.new")

	newCfg.withWriter{ writer ->
		writer.write(template())
		writer.write(listen("web", "http", jenkinsInstances))
		writer.write(listen("jnlp", "tcp", jenkinsInstances))
	}

	new File("{HA_PROXY_CFG}").renameTo("${HA_PROXY_CFG}.bkup")
	newCfg.renameTo("${HA_PROXY_CFG}")	

	reload()
}

def reload(){
	println("JocDynamicProxy: reloading haproxy")
	["service", "haproxy", "reload"].execute()
}

def getCliPort(url){
	Process result = ["curl", "-I", "-s", "${url}"].execute() | ["grep", "-Fi", "X-Jenkins-CLI2-Port"].execute()
	def header = result.text
	return header ? header.minus("X-Jenkins-CLI2-Port: ") : null
}
 
def acl(name, mode, jenkinsInstance){
	def acl = ""
	if(mode == "http"){
		acl = """
			acl ${jenkinsInstance.name}-req path_reg ^/${jenkinsInstance.name}*
			use_backend ${jenkinsInstance.name}-${name} if ${jenkinsInstance.name}-req
		"""
	}
	if(mode == "tcp" && jenkinsInstance.jnlpPort){
		acl = """
			acl ${jenkinsInstance.name}-req dst_port eq ${jenkinsInstance.jnlpPort}
			use_backend ${jenkinsInstance.name}-${name} if ${jenkinsInstance.name}-req
		"""
	}
	return acl
}

//TODO refactor me
def backends(name, mode, jenkinsInstances){
	def backends = ""
	jenkinsInstances.each{
		backends += backend(name, mode, it)
	}
	return backends
}

def backend(name, mode, jenkinsInstance){
	def backend = """
		backend ${jenkinsInstance.name}-${name}
    		balance roundrobin
    		mode ${mode}
    """
    if(mode == "http"){
    	backend += "option httpchk HEAD /${jenkinsInstance.name}/ha/health-check $NEWLINE"
    }
    jenkinsInstance.servers.eachWithIndex{ url, i ->
    	def server = url
    	if(mode == "http"){
			backend += "server ${jenkinsInstance.name}-${name}-${i} ${server} check $NEWLINE"
    	}else if(mode == "tcp" && jenkinsInstance.jnlpPort){
			server = server.replace('8080', jenkinsInstance.jnlpPort)
			backend += "server ${jenkinsInstance.name}-${name}-${i} ${server} $NEWLINE"
		}
	}
	return backend
}

def frontend(name, mode, jenkinsInstances){
	def ports = jenkinsInstances.collect{ mode == 'http' ? it.httpPort : it.jnlpPort }.findAll()
	def frontend = """
		########### ${name} traffic #############
		frontend ${name}
			mode ${mode}
			option ${mode}log
	"""
	ports.unique().each{
		frontend += "bind *:${it} ${NEWLINE}"
	}
	jenkinsInstances.each{
		frontend += acl(name, mode, it)
	}
	return frontend
}

def listen(name, mode, jenkinsInstances){
	return frontend(name, mode, jenkinsInstances) + backends(name, mode, jenkinsInstances)
}

def template(jenkinsInstances){
	return """
	#---------------------------------------------------------------------
	# Global settings
	#---------------------------------------------------------------------
	global
	    chroot      /var/lib/haproxy
	    pidfile     /var/run/haproxy.pid
	    maxconn     4000
	    user        haproxy
	    group       haproxy
	    daemon
	    stats socket /var/lib/haproxy/stats
	    log /dev/log local0 info
	    log /dev/log local0 notice
	    debug
	    
	defaults
	    log                     global
	    option                  dontlognull
	    option                  http-server-close
	    option                  forwardfor except 127.0.0.0/8
	    option                  redispatch
	    option                  abortonclose
	    retries                 3
	    maxconn                 3000
	    timeout http-request    10s
	    timeout queue           1m
	    timeout connect         10s
	    timeout client          1m
	    timeout server          1m
	    timeout http-keep-alive 10s
	    timeout check           500
	    default-server          inter 5s downinter 500 rise 1 fall 1
	"""
}

@EqualsAndHashCode
class JenkinsInstance {
	def name
	def jenkinsHome
	def httpPort
	def jnlpPort
	Set<String> servers
}
