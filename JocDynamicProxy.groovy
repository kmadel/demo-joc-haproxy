@Grab('commons-io:commons-io:2.4')

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.NameFileFilter
import org.apache.commons.io.filefilter.TrueFileFilter

NEWLINE = System.getProperty("line.separator")
JENKINS_OC_HOME = "/mnt/var/lib/jenkins-oc"
CLIENT_MASTERS = [:]

run(args)

def run(jocServers){
	if(jocServers[0] == null){
		throw new Exception("Enter JOC backends as Groovy args.")
	}
	while(true){
		monitor(jocServers.toList())
		println("JocDynamicProxy: polling")
		Thread.sleep(5000);
	}
}

def monitor(List jocServers){
	// grab all connected client masters from joc
	File jocHome = new File("${JENKINS_OC_HOME}/jobs")
	FileFilter configFilter = new NameFileFilter("config.xml")
	Collection<File> configs = FileUtils.listFiles(jocHome, configFilter, TrueFileFilter.INSTANCE)

	def newClientMasters = [:]
	configs.each{ file ->
	        def config = new XmlParser().parse(file)
	        if (config.name() == 'com.cloudbees.opscenter.server.model.ClientMaster'){
	                newClientMasters[config.encodedName.text()] = config.description.text().tokenize()
	        }
	}

	//dont reload / restart unless there are new masters
	if(newClientMasters != CLIENT_MASTERS){
		println "Polling found new client masters"
		// update haproxy.cfg
		File newCfg = new File("/etc/haproxy/haproxy-new.cfg")

		newCfg.withWriter{ writer ->
			writer.write(template(aclsFromClientMasters(newClientMasters), jocServers))
			writer.write(backendsFromClientMasters(newClientMasters))
		}

		new File("/etc/haproxy/haproxy.cfg").renameTo("/etc/haproxy/haproxy.cfg.bkup")
		newCfg.renameTo("/etc/haproxy/haproxy.cfg")	

		restart()

		// now determine jnlp port
		newClientMasters.each{
			def port = getCliPort("http://proxy.jenkins-haproxy.dev.beedemo.io/" + it.key + "/");
			println("CLI Port: ${port}") //TODO need to retrieve result of execute from groovy
		}

	}
	CLIENT_MASTERS = newClientMasters
}



// functions

// reload haproxy.cfg (safe restart)
def restart(){
	// """haproxy -f /etc/haproxy/haproxy.cfg -p /var/run/haproxy.pid -sf \$(cat /var/run/haproxy.pid)""".execute()
	println("JocDynamicProxy: restarting haproxy")
	["service", "haproxy", "restart"].execute().waitFor()
}

def getCliPort(url){
	//TODO finish including restart
	Process result = ["curl", "-I", "-s", "${url}"].execute() | ["grep", "-Fi", "X-Jenkins-CLI2-Port"].execute()
	return result.text.minus("X-Jenkins-CLI2-Port: ")
}
 
def aclsFromClientMasters(clientMasters){
	def acls = ""
	clientMasters.each{
		acls += acl(it.key, it.key)
	}
	return acls
}

def acl(name, prefix){
	return """
		acl jebc-${name}-req path_reg ^/${prefix}*
		use_backend ${name}-http if jebc-${name}-req
	"""
}

def backendsFromClientMasters(clientMasters){
	def backends = ""
	clientMasters.each{
		backends += backend(it.key, '/' + it.key, it.value)
	}
	return backends
}

def backend(name, prefix, List servers){
	def backend = """
		backend ${name}-http
    	balance                 roundrobin
    	option                  httpchk HEAD ${prefix}/ha/health-check
    """
    servers.eachWithIndex{ url, i ->
		backend += "server ${name}-http-${i} ${url} check $NEWLINE"
	}
	return backend
}

def template(acls, List servers){
	def template = """
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
	    mode                    http
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

	########### http traffic #############

	frontend http
	    bind                    *:80
	    option                  httplog

	"""

	template += acls
	template += "default_backend joc-http"
	template += backend('joc', "", servers)

	template += """
	########### jnlp traffic #############

	frontend jnlp
	    mode                    tcp
	    option                  tcplog
	    bind                    *:49187
	    use_backend             joc-jnlp

	backend joc-jnlp
	    mode tcp
	    balance                 roundrobin
	    server                  joc-jnlp-1 joc-1.jenkins-operations-center.dev.beedemo.io:49187
	    server                  joc-jnlp-2 joc-2.jenkins-operations-center.dev.beedemo.io:49187

	"""

	return template
}
