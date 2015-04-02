@Grab('commons-io:commons-io:2.4')

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.NameFileFilter
import org.apache.commons.io.filefilter.TrueFileFilter
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

NEWLINE = System.getProperty("line.separator")
JENKINS_OC_HOME = "/mnt/var/lib/jenkins-oc"
HA_PROXY_CFG = "/etc/haproxy/haproxy.cfg"
PRIOR_JENKINS_INSTANCES = [] as Set

run(args)

def run(args){
	def cli = new CliBuilder(usage: 'jocproxy.groovy -[hnd] [servers]')
	cli.with {
		h longOpt: 'help', 'Show usage information'
        p longOpt: 'path', args: 1, argName: 'path', 'Path to JOC server (httpPrefix)'
        j longOpt: 'joc-home', args: 1, argName: 'joc-home', 'JOC home directory'
        d longOpt: 'delay', args: 1, argName: 'delay', 'Delay between polling interval in ms'
    }
    def options = cli.parse(args)

    if (!options || options.h) {
    	cli.usage()
        return
    }

    ["service", "rsyslog", "restart"].execute()
    ["service", "haproxy", "restart"].execute()

    def name = options.p ? options.p.minus('/') : 'joc'
    def path = options.p ? options.p : ''
    def jocHome = options.j ? options.j : JENKINS_OC_HOME

	JenkinsInstance joc = new JenkinsInstance(name: name, path: path, jenkinsHome: jocHome, servers: options.arguments(), httpPort: 80)
	while(true){
		monitor(joc)
		Thread.sleep(options.d ? options.d as int : 5000)
		// new Timer().schedule({ monitor(joc) } as TimerTask, 1000, 5000)
	}
}

def monitor(JenkinsInstance joc){
	println("jocproxy: polling")
	Set jenkinsInstances = []
	jenkinsInstances.add(joc)

	// grab all connected client masters from joc
	File jocJobs = new File("${joc.jenkinsHome}/jobs")
	FileFilter configFilter = new NameFileFilter("config.xml")
	Collection<File> configs = FileUtils.listFiles(jocJobs, configFilter, TrueFileFilter.INSTANCE)

	//create a JenkinsIntance for each
	configs.each{ file ->
        def config = new XmlParser().parse(file)
        if (config.name() == 'com.cloudbees.opscenter.server.model.ClientMaster'){
        	def name = config.encodedName.text()
        	def clientMaster = new JenkinsInstance(name: name, path: '/'.plus(name), servers: config.description.text().tokenize())
            jenkinsInstances << clientMaster
        }
	}

	//dont reload unless there are new masters
	if(jenkinsInstances != PRIOR_JENKINS_INSTANCES){
		println "jocproxy: detected jenkins instance change"
		// update haproxy.cfg
		updateAndReload(jenkinsInstances)

		// now determine jnlp ports
		jenkinsInstances.each{
			def port = getCliPort("http://localhost" + it.path + "/")
			it.jnlpPort = port != null ? port.trim() : null
		}

		updateAndReload(jenkinsInstances)
	}
	PRIOR_JENKINS_INSTANCES = jenkinsInstances
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

	if(!reload()){
		println("jocproxy: haproxy failed reload; reverting to prior config")
		new File("{HA_PROXY_CFG}").renameTo("${HA_PROXY_CFG}.failed")
		new File("{HA_PROXY_CFG}.bkup").renameTo("${HA_PROXY_CFG}")
		reload()
	}
}

def reload(){
	println("jocproxy: reloading haproxy")
	def proc = ["service", "haproxy", "reload"].execute()
	proc.waitFor()
	return proc.exitValue() == 0
}

def getCliPort(url){
	Process result = ["curl", "-I", "-s", "${url}"].execute() | ["grep", "-Fi", "X-Jenkins-CLI2-Port"].execute()
	def header = result.text
	return header ? header.minus("X-Jenkins-CLI2-Port: ") : null
}
 
def acl(frontendName, mode, jenkinsInstance){
	def acl = ""
	if(mode == "http"){
		if(jenkinsInstance.path){
			acl = """
				acl ${jenkinsInstance.name}-req path_reg ^${jenkinsInstance.path}*
				use_backend ${jenkinsInstance.name}-${frontendName} if ${jenkinsInstance.name}-req
			"""
		}else{
			acl = """
				default_backend ${jenkinsInstance.name}-${frontendName}
			"""
		}
	}
	if(mode == "tcp" && jenkinsInstance.jnlpPort){
		acl = """
			acl ${jenkinsInstance.name}-req dst_port eq ${jenkinsInstance.jnlpPort}
			use_backend ${jenkinsInstance.name}-${frontendName} if ${jenkinsInstance.name}-req
		"""
	}
	return acl
}

//TODO refactor me
def backends(frontendName, mode, jenkinsInstances){
	def backends = ""
	jenkinsInstances.each{
		backends += backend(frontendName, mode, it)
	}
	return backends
}

def backend(frontendName, mode, jenkinsInstance){
	def backend = """
		backend ${jenkinsInstance.name}-${frontendName}
    		balance roundrobin
    		mode ${mode}
    """
    if(mode == "http"){
    	backend += "option httpchk HEAD ${jenkinsInstance.path}/ha/health-check $NEWLINE"
    }
    jenkinsInstance.servers.eachWithIndex{ url, i ->
    	def server = url
    	if(mode == "http"){
			backend += "server ${jenkinsInstance.name}-${frontendName}-${i} ${server} check $NEWLINE"
    	}else if(mode == "tcp" && jenkinsInstance.jnlpPort){
			server = server.replace('8080', jenkinsInstance.jnlpPort)
			backend += "server ${jenkinsInstance.name}-${frontendName}-${i} ${server} $NEWLINE"
		}
	}
	return backend
}

def frontend(frontendName, mode, jenkinsInstances){
	def ports = jenkinsInstances.collect{ mode == 'http' ? it.httpPort : it.jnlpPort }.findAll()
	def frontend = """
		########### ${frontendName} traffic #############
		frontend ${frontendName}
			mode ${mode}
			option ${mode}log
	"""
	ports.unique().each{
		frontend += "bind *:${it} ${NEWLINE}"
	}
	jenkinsInstances.each{
		frontend += acl(frontendName, mode, it)
	}
	return frontend
}

def listen(frontendName, mode, jenkinsInstances){
	return frontend(frontendName, mode, jenkinsInstances) + backends(frontendName, mode, jenkinsInstances)
}

def template(){
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

@ToString(includeNames=true)
class JenkinsInstance {
	String name, path, jenkinsHome, httpPort, jnlpPort
	Set<String> servers

	public boolean equals(Object that){
		return (that instanceof JenkinsInstance 
			&& this.name == that.name
			&& this.servers == that.servers)
	}
}
